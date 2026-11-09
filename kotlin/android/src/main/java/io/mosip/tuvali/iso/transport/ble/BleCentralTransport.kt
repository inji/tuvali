package io.mosip.tuvali.iso.transport.ble

import android.util.Log
import io.mosip.tuvali.ble.central.coroutine.GattConnectionState
import io.mosip.tuvali.ble.central.coroutine.GattOperation
import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.ble.central.coroutine.SuspendingGattClient
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.iso.exception.GattOperationFailedException
import io.mosip.tuvali.iso.exception.IsoTransportException
import io.mosip.tuvali.iso.transport.IsoChunker
import io.mosip.tuvali.iso.transport.MdocTransport
import io.mosip.tuvali.iso.transport.ReaderEvent
import io.mosip.tuvali.iso.transport.ble.MdocReaderServiceCharacteristics as Characteristics
import io.mosip.tuvali.transfer.Util.Companion.getLogTag
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * The ISO 18013-5 BLE transport with the mdoc as GATT client (clauses 8.3.3.1.1.3 to 8.3.3.1.1.8).
 *
 * [open] finds the reader advertising [serviceUuid], checks its Ident against [expectedIdent]
 * (leaving a reader that does not match and scanning on), subscribes to State and Server2Client,
 * negotiates the MTU and writes Start. A link lost before Start is retried with a new connection;
 * after Start nothing reconnects, and any failure ends the session. A GATT timeout is never
 * retried on the same connection, since a late answer could be mistaken for the retry's.
 *
 * One session owns an instance; [send] and [receive] are meant to be called from that session.
 */
internal class BleCentralTransport(
  private val serviceUuid: UUID,
  expectedIdent: ByteArray,
  private val readerFinder: ReaderFinder,
  private val clientFactory: () -> SuspendingGattClient,
  private val maxConnectAttempts: Int = 3,
) : MdocTransport {
  private val logTag = getLogTag(javaClass.simpleName)
  private val expectedIdent = expectedIdent.copyOf()

  private var client: SuspendingGattClient? = null
  private var chunker: IsoChunker? = null
  private var readerEnded = false

  override suspend fun open() {
    check(client == null) { "Transport is already open" }
    val excludedReaders = mutableSetOf<String>()
    // only connections lost before Start count; readers left for a wrong Ident do not
    var lostConnections = 0

    while (true) {
      val device = readerFinder.findReader(serviceUuid, excludedReaders)
      val candidate = clientFactory()
      try {
        candidate.connect(device)
        if (!isExpectedReader(candidate)) {
          Log.i(logTag, "Ident of ${device.address} does not match; scanning on")
          candidate.close()
          excludedReaders += device.address
          continue
        }
        candidate.enableNotifications(serviceUuid, Characteristics.STATE)
        candidate.enableNotifications(serviceUuid, Characteristics.SERVER_TO_CLIENT)
        val mtu = candidate.requestMtu()
        writeState(candidate, ConnectionStateValue.START)
        chunker = BleChunker(mtu)
        client = candidate
        return
      } catch (exception: Exception) {
        candidate.close()
        if (!isLostBeforeStart(exception) || ++lostConnections >= maxConnectAttempts) throw exception
        Log.i(logTag, "Link lost before Start ($lostConnections of $maxConnectAttempts); connecting again")
      }
    }
  }

  override suspend fun send(message: ByteArray) {
    val openClient = checkNotNull(client) { "Transport is not open" }
    check(!readerEnded) { "Reader already ended the session" }
    checkNotNull(chunker).split(message).forEach { part ->
      openClient.writeWithoutResponse(serviceUuid, Characteristics.CLIENT_TO_SERVER, part)
    }
  }

  /** Waits for the reader's next whole message, or End. Throws if the link drops while waiting. */
  override suspend fun receive(): ReaderEvent {
    val openClient = checkNotNull(client) { "Transport is not open" }
    val openChunker = checkNotNull(chunker)
    while (true) {
      val notification = try {
        openClient.notifications.first()
      } catch (closed: NoSuchElementException) {
        throw GattDisconnectedException(null)
      }
      when (notification.characteristicUuid) {
        Characteristics.STATE -> if (notification.value.contentEquals(byteArrayOf(ConnectionStateValue.END))) {
          readerEnded = true
          return ReaderEvent.Ended
        }
        Characteristics.SERVER_TO_CLIENT ->
          openChunker.addPiece(notification.value)?.let { return ReaderEvent.Message(it) }
      }
    }
  }

  /**
   * Ends the connection (clause 8.3.3.1.1.7): writes End unless the reader already did, unsubscribes
   * from State and Server2Client, and disconnects. Each step is best effort, so a lost link still
   * ends in a closed client.
   */
  override suspend fun close() {
    val openClient = client ?: return
    client = null
    chunker = null
    val linkUp = openClient.connectionState.value == GattConnectionState.Connected
    if (linkUp && !readerEnded) {
      bestEffort("write End") { writeState(openClient, ConnectionStateValue.END) }
    }
    if (linkUp) {
      bestEffort("unsubscribe from State") { openClient.disableNotifications(serviceUuid, Characteristics.STATE) }
      bestEffort("unsubscribe from Server2Client") {
        openClient.disableNotifications(serviceUuid, Characteristics.SERVER_TO_CLIENT)
      }
    }
    openClient.close()
  }

  private suspend fun writeState(gattClient: SuspendingGattClient, value: Byte) =
    gattClient.writeWithoutResponse(serviceUuid, Characteristics.STATE, byteArrayOf(value))

  private suspend fun isExpectedReader(candidate: SuspendingGattClient): Boolean {
    val service = candidate.findService(serviceUuid)
    Characteristics.ALL.forEach { characteristic ->
      if (service.getCharacteristic(characteristic) == null) {
        throw IsoTransportException("Reader service has no characteristic $characteristic")
      }
    }
    return candidate.read(serviceUuid, Characteristics.IDENT).contentEquals(expectedIdent)
  }

  private fun isLostBeforeStart(exception: Exception): Boolean = when (exception) {
    is GattDisconnectedException -> exception.reason == LinkLossReason.REMOTE_DISCONNECTED
    is GattOperationFailedException -> exception.operation == GattOperation.CONNECT
    else -> false
  }

  private suspend fun bestEffort(step: String, action: suspend () -> Unit) {
    try {
      action()
    } catch (exception: Exception) {
      Log.w(logTag, "Could not $step while closing: ${exception.message}")
    }
  }
}
