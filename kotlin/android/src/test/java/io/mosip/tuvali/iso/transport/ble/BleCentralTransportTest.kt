package io.mosip.tuvali.iso.transport.ble

import io.mosip.tuvali.ble.central.coroutine.GattOperation
import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.iso.exception.GattOperationFailedException
import io.mosip.tuvali.iso.exception.GattOperationTimeoutException
import io.mosip.tuvali.iso.exception.IsoTransportException
import io.mosip.tuvali.iso.transport.ReaderEvent
import io.mosip.tuvali.testutils.ble.MockIsoReader
import io.mosip.tuvali.testutils.ble.READER_IDENT
import io.mosip.tuvali.testutils.ble.startOperation
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ISO 18013-5 BLE transport with the mdoc as GATT client: clauses 8.3.3.1.1.3 to 8.3.3.1.1.8. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleCentralTransportTest {

  private val sessionUuid = UUID.fromString("45efef74-2b2c-4837-a9a3-b0e1d05a6917")
  private val expectedIdent = "ec2667104dccaf62fc6976cc75f1abdb".hexToBytes()

  /** Readers the scan will find, in order; the transport gets the client of the last one found. */
  private val readersToFind = ArrayDeque<MockIsoReader>()
  private val scanRequests = mutableListOf<Set<String>>()
  private var lastFound: MockIsoReader? = null

  private val finder = ReaderFinder { _, excluded ->
    scanRequests += excluded
    val reader = readersToFind.removeFirst()
    lastFound = reader
    reader.device
  }

  private fun transport(maxConnectAttempts: Int = 3) =
    BleCentralTransport(sessionUuid, expectedIdent, finder, { checkNotNull(lastFound).client }, maxConnectAttempts)

  private fun reader(
    ident: ByteArray = expectedIdent,
    mtu: Int = 185,
    address: String = "AA:AA:AA:AA:AA:AA",
    missingCharacteristic: UUID? = null,
  ) = MockIsoReader(sessionUuid, ident, mtu, address, missingCharacteristic).also { readersToFind += it }

  // ---------- opening the connection ----------

  @Test
  fun `open checks Ident, subscribes to State and Server2Client, asks for the MTU, then writes Start`() = runTest {
    val reader = reader()

    transport().open()

    assertEquals(
      listOf("connect", "read ident", "subscribe 05", "subscribe 07", "mtu", "state 01"),
      reader.events,
    )
  }

  @Test
  fun `open writes Start even when the reader sent its first message early`() = runTest {
    val reader = reader()
    // an eager reader notifies its SessionEstablishment before the wallet has written Start
    reader.sendMessage("a1".hexToBytes())

    val transport = transport().apply { open() }

    assertTrue("state 01" in reader.events)
    assertEquals("a1", (transport.receive() as ReaderEvent.Message).bytes.toHex())
  }

  @Test
  fun `open leaves a reader whose Ident does not match and connects to the next one`() = runTest {
    // a different reader nearby that also advertises the session UUID
    val wrongReader = reader(ident = ByteArray(16), address = "AA:AA:AA:AA:AA:AA")
    val rightReader = reader(address = "BB:BB:BB:BB:BB:BB")

    transport().open()

    assertEquals(listOf("connect", "read ident", "close"), wrongReader.events)
    assertTrue("state 01" in rightReader.events)
    assertEquals(setOf("AA:AA:AA:AA:AA:AA"), scanRequests.last())
  }

  @Test
  fun `open throws IsoTransportException and closes the connection when a Table 12 characteristic is missing`() = runTest {
    val reader = reader(missingCharacteristic = READER_IDENT)

    val failure = runCatching { transport().open() }.exceptionOrNull()

    assertTrue(failure is IsoTransportException)
    assertTrue(reader.wasClosed)
  }

  @Test
  fun `open connects again when the link drops before Start`() = runTest {
    val firstTry = reader().apply { failOnSubscribe = GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED) }
    val secondTry = reader()

    transport().open()

    assertTrue(firstTry.wasClosed)
    assertFalse("state 01" in firstTry.events)
    assertTrue("state 01" in secondTry.events)
  }

  @Test
  fun `open throws GattDisconnectedException when the link keeps dropping before Start`() = runTest {
    repeat(2) { reader().failOnSubscribe = GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED) }

    val failure = runCatching { transport(maxConnectAttempts = 2).open() }.exceptionOrNull()

    assertTrue(failure is GattDisconnectedException)
  }

  @Test
  fun `open connects again when the first connection attempt fails`() = runTest {
    // 133 is Android's generic GATT_ERROR for a failed connection attempt
    reader().failOnConnect = GattOperationFailedException(GattOperation.CONNECT, "status 133")
    val secondTry = reader()

    transport().open()

    assertTrue("state 01" in secondTry.events)
  }

  @Test
  fun `open does not count readers with a wrong Ident as lost connections`() = runTest {
    // two wrong readers nearby, then one link drop, with room for two connection attempts
    reader(ident = ByteArray(16), address = "AA:AA:AA:AA:AA:AA")
    reader(ident = ByteArray(16), address = "BB:BB:BB:BB:BB:BB")
    reader(address = "CC:CC:CC:CC:CC:CC").failOnSubscribe = GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED)
    val finalTry = reader(address = "CC:CC:CC:CC:CC:CC")

    transport(maxConnectAttempts = 2).open()

    assertTrue("state 01" in finalTry.events)
  }

  @Test
  fun `open does not retry after a GATT operation times out`() = runTest {
    val reader = reader().apply { failOnIdentRead = GattOperationTimeoutException(GattOperation.READ_CHARACTERISTIC, 5.seconds) }
    reader()

    val failure = runCatching { transport().open() }.exceptionOrNull()

    assertTrue(failure is GattOperationTimeoutException)
    assertTrue(reader.wasClosed)
    assertEquals(1, scanRequests.size)
  }

  @Test
  fun `open does not retry when Bluetooth is switched off before Start`() = runTest {
    reader().failOnSubscribe = GattDisconnectedException(LinkLossReason.BLUETOOTH_TURNED_OFF)
    reader()

    val failure = runCatching { transport().open() }.exceptionOrNull()

    assertEquals(LinkLossReason.BLUETOOTH_TURNED_OFF, (failure as GattDisconnectedException).reason)
    assertEquals(1, scanRequests.size)
  }

  // ---------- sending ----------

  @Test
  fun `send writes the message to Client2Server in parts of MTU minus 3 bytes`() = runTest {
    // MTU 23: parts of 20 bytes, 19 of them message
    val reader = reader(mtu = 23)
    val transport = transport().apply { open() }
    val message = ByteArray(50) { it.toByte() }

    transport.send(message)

    assertEquals(listOf(20, 20, 13), reader.clientToServerParts.map { it.size })
    assertEquals(message.toHex(), reader.receivedMessages().single().toHex())
  }

  @Test
  fun `send throws IllegalStateException before open`() = runTest {
    val transport = transport()

    val failure = runCatching { transport.send(byteArrayOf(1)) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
  }

  @Test
  fun `send throws GattDisconnectedException after the link drops and does not reconnect`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    reader.dropLink()

    val failure = runCatching { transport.send(byteArrayOf(1)) }.exceptionOrNull()

    assertTrue(failure is GattDisconnectedException)
    assertEquals(1, scanRequests.size)
  }

  // ---------- receiving ----------

  @Test
  fun `receive returns a whole message rebuilt from the reader's parts`() = runTest {
    val reader = reader(mtu = 23)
    val transport = transport().apply { open() }
    val message = ByteArray(60) { (it * 7).toByte() }
    reader.sendMessage(message)

    val event = transport.receive()

    assertEquals(message.toHex(), (event as ReaderEvent.Message).bytes.toHex())
  }

  @Test
  fun `receive returns Ended when the reader writes End to State`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    reader.sendState(0x02)

    val event = transport.receive()

    assertEquals(ReaderEvent.Ended, event)
  }

  @Test
  fun `receive returns Ended when the reader writes End halfway through a message`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    // the first part of a message (more parts announced), then End
    reader.sendRawPart("01a1a2".hexToBytes())
    reader.sendState(0x02)

    val event = transport.receive()

    assertEquals(ReaderEvent.Ended, event)
  }

  @Test
  fun `receive ignores State values other than End`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    reader.sendState(0x01)
    reader.sendMessage("a1".hexToBytes())

    val event = transport.receive()

    assertEquals("a1", (event as ReaderEvent.Message).bytes.toHex())
  }

  @Test
  fun `receive throws GattDisconnectedException when the link drops while waiting`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    val receiving = startOperation { runCatching { transport.receive() } }

    reader.dropLink()

    assertTrue(receiving.await().exceptionOrNull() is GattDisconnectedException)
  }

  @Test
  fun `receive throws IsoTransportException for a part with an unknown header`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    reader.sendRawPart("03a1".hexToBytes())

    val failure = runCatching { transport.receive() }.exceptionOrNull()

    assertTrue(failure is IsoTransportException)
  }

  @Test
  fun `send throws IllegalStateException after the reader ended the session`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    reader.sendState(0x02)
    transport.receive()

    val failure = runCatching { transport.send(byteArrayOf(1)) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
  }

  // ---------- closing ----------

  @Test
  fun `close writes End, unsubscribes from State and Server2Client, then disconnects`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }

    transport.close()

    assertEquals(listOf("state 02", "unsubscribe 05", "unsubscribe 07", "close"), reader.events.takeLast(4))
  }

  @Test
  fun `close does not write End when the reader already ended, but still unsubscribes and disconnects`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    reader.sendState(0x02)
    transport.receive()

    transport.close()

    assertEquals(listOf("unsubscribe 05", "unsubscribe 07", "close"), reader.events.takeLast(3))
    assertFalse("state 02" in reader.events)
  }

  @Test
  fun `close still disconnects when the link is already gone`() = runTest {
    val reader = reader()
    val transport = transport().apply { open() }
    reader.dropLink()

    transport.close()

    assertTrue(reader.wasClosed)
  }

  @Test
  fun `close does nothing when open never connected`() = runTest {
    val transport = transport()

    transport.close()

    assertTrue(scanRequests.isEmpty())
  }
}
