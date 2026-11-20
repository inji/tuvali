package io.mosip.tuvali.testutils.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mosip.tuvali.ble.central.coroutine.GattConnectionState
import io.mosip.tuvali.ble.central.coroutine.GattNotification
import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.ble.central.coroutine.SuspendingGattClient
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.testutils.toHex
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Table 12 characteristics of the mdoc reader service (ISO 18013-5 clause 8.3.3.1.1.4). */
internal val READER_STATE: UUID = UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
internal val READER_CLIENT_TO_SERVER: UUID = UUID.fromString("00000006-a123-48ce-896b-4c76973373e6")
internal val READER_SERVER_TO_CLIENT: UUID = UUID.fromString("00000007-a123-48ce-896b-4c76973373e6")
internal val READER_IDENT: UUID = UUID.fromString("00000008-a123-48ce-896b-4c76973373e6")

/**
 * A scripted ISO 18013-5 reader as seen through one [SuspendingGattClient] connection.
 *
 * It serves the reader service with an Ident, records what the wallet subscribes to and writes
 * (in order, as [events]), and lets the test push chunked messages, End or a link drop. Its
 * chunking and reassembly are written independently of the production code.
 */
internal class MockIsoReader(
  private val serviceUuid: UUID,
  private val ident: ByteArray,
  val mtu: Int = 185,
  readerAddress: String = "AA:AA:AA:AA:AA:AA",
  missingCharacteristic: UUID? = null,
) {
  val device: BluetoothDevice = mockk(relaxed = true) { every { this@mockk.address } returns readerAddress }
  val client: SuspendingGattClient = mockk(relaxed = true)

  /** What the wallet did on this connection, e.g. "subscribe 05", "mtu", "state 01", "unsubscribe 07", "close". */
  val events = mutableListOf<String>()
  val clientToServerParts = mutableListOf<ByteArray>()

  /** Set to make the next matching step fail, e.g. a link drop while subscribing. */
  var failOnConnect: Exception? = null
  var failOnSubscribe: Exception? = null
  var failOnIdentRead: Exception? = null

  private val notifications = Channel<GattNotification>(Channel.UNLIMITED)
  private val connectionState = MutableStateFlow<GattConnectionState>(GattConnectionState.Connected)
  private var linkDropped = false

  init {
    val service = mockk<BluetoothGattService>(relaxed = true)
    every { service.uuid } returns serviceUuid
    every { service.getCharacteristic(any()) } answers {
      val uuid = firstArg<UUID>()
      if (uuid == missingCharacteristic) null else mockk<BluetoothGattCharacteristic>(relaxed = true)
    }

    coEvery { client.connect(any()) } answers {
      failOnConnect?.let { throw it }
      events += "connect"
    }
    // stubbed with its default arguments (as the transport calls it): mockk 1.12.5 cannot build
    // an any() matcher for the Duration (value class) parameter and recurses forever
    coEvery { client.findService(serviceUuid) } returns service
    coEvery { client.read(serviceUuid, READER_IDENT) } answers {
      failOnIdentRead?.let { throw it }
      events += "read ident"
      ident
    }
    coEvery { client.enableNotifications(serviceUuid, any(), any()) } answers {
      failOnSubscribe?.let { throw it }
      events += "subscribe ${shortName(secondArg())}"
    }
    coEvery { client.disableNotifications(serviceUuid, any(), any()) } answers {
      failIfDropped()
      events += "unsubscribe ${shortName(secondArg())}"
    }
    coEvery { client.requestMtu(any()) } answers {
      events += "mtu"
      mtu
    }
    coEvery { client.writeWithoutResponse(serviceUuid, any(), any()) } answers {
      failIfDropped()
      val value = thirdArg<ByteArray>()
      when (secondArg<UUID>()) {
        READER_STATE -> events += "state ${value.toHex()}"
        READER_CLIENT_TO_SERVER -> clientToServerParts += value
      }
    }
    every { client.notifications } returns notifications.receiveAsFlow()
    every { client.connectionState } returns connectionState
    every { client.close() } answers {
      events += "close"
      notifications.close()
    }
  }

  val wasClosed: Boolean get() = "close" in events

  /** Notifies [message] on Server2Client in parts of MTU − 3 bytes with 0x01 / 0x00 headers. */
  fun sendMessage(message: ByteArray) {
    val payloadPerPart = mtu - 4
    val starts = if (message.isEmpty()) listOf(0) else (message.indices step payloadPerPart).toList()
    starts.forEach { start ->
      val end = minOf(start + payloadPerPart, message.size)
      val header: Byte = if (end < message.size) 0x01 else 0x00
      sendRawPart(byteArrayOf(header) + message.copyOfRange(start, end))
    }
  }

  fun sendRawPart(part: ByteArray) {
    notifications.trySend(GattNotification(READER_SERVER_TO_CLIENT, part))
  }

  fun sendState(value: Byte) {
    notifications.trySend(GattNotification(READER_STATE, byteArrayOf(value)))
  }

  fun dropLink() {
    linkDropped = true
    connectionState.value = GattConnectionState.Lost(LinkLossReason.REMOTE_DISCONNECTED)
    notifications.close(GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED))
  }

  /** The messages the wallet wrote to Client2Server, rebuilt from their parts. */
  fun receivedMessages(): List<ByteArray> {
    val messages = mutableListOf<ByteArray>()
    val current = ByteArrayOutputStream()
    clientToServerParts.forEach { part ->
      current.write(part, 1, part.size - 1)
      if (part[0] == 0x00.toByte()) {
        messages += current.toByteArray()
        current.reset()
      }
    }
    return messages
  }

  private fun failIfDropped() {
    if (linkDropped) throw GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED)
  }

  private fun shortName(uuid: UUID): String = uuid.toString().substring(6, 8)
}
