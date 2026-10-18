package io.mosip.tuvali.ble.central.coroutine

import android.bluetooth.BluetoothGattCharacteristic
import io.mockk.verify
import io.mosip.tuvali.ble.exception.GattDisconnectedException
import io.mosip.tuvali.ble.exception.GattOperationTimeoutException
import io.mosip.tuvali.testutils.ble.MockAndroidBluetooth
import io.mosip.tuvali.testutils.ble.connectSuccessfully
import io.mosip.tuvali.testutils.ble.startOperation
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Android runs one GATT operation at a time per connection; the client must enforce that. */
@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingGattClientConcurrencyTest {

  private val serviceUuid = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
  private val firstUuid = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
  private val secondUuid = UUID.fromString("0000a003-0000-1000-8000-00805f9b34fb")

  private val bluetooth = MockAndroidBluetooth()
  private val timeouts = GattTimeouts(connect = 10.seconds, operation = 5.seconds)
  private val client = SuspendingGattClient(bluetooth.context, timeouts)

  private val service = bluetooth.addService(serviceUuid)
  private val first = bluetooth.addCharacteristic(service, firstUuid, BluetoothGattCharacteristic.PROPERTY_READ)
  private val second = bluetooth.addCharacteristic(service, secondUuid, BluetoothGattCharacteristic.PROPERTY_READ)

  @Test
  fun `a second operation does not reach Android until the first one completes`() = runTest {
    connectSuccessfully(client, bluetooth)
    val firstRead = startOperation { client.read(serviceUuid, firstUuid) }

    val secondRead = startOperation { client.read(serviceUuid, secondUuid) }

    verify(exactly = 0) { bluetooth.gatt.readCharacteristic(second) }
    bluetooth.reportRead(first, "01".hexToBytes())
    runCurrent()
    verify(exactly = 1) { bluetooth.gatt.readCharacteristic(second) }
    bluetooth.reportRead(second, "02".hexToBytes())
    assertEquals("01", firstRead.await().toHex())
    assertEquals("02", secondRead.await().toHex())
  }

  @Test
  fun `a late read result for a different characteristic does not complete the pending read`() = runTest {
    connectSuccessfully(client, bluetooth)
    val firstRead = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }

    // a stale answer, e.g. for a read that timed out or was cancelled earlier
    bluetooth.reportRead(second, "02".hexToBytes())
    runCurrent()

    assertTrue(firstRead.isActive)
  }

  @Test
  fun `an operation that times out throws GattOperationTimeoutException`() = runTest {
    connectSuccessfully(client, bluetooth)
    val read = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }

    advanceTimeBy(timeouts.operation + 1.milliseconds)

    assertTrue(read.await().exceptionOrNull() is GattOperationTimeoutException)
  }

  @Test
  fun `a callback arriving after its operation timed out is ignored and the next operation still works`() = runTest {
    connectSuccessfully(client, bluetooth)
    val timedOut = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }
    advanceTimeBy(timeouts.operation + 1.milliseconds)
    timedOut.await()
    val nextRead = startOperation { client.read(serviceUuid, secondUuid) }

    bluetooth.reportRead(first, "01".hexToBytes())
    bluetooth.reportRead(second, "02".hexToBytes())

    assertEquals("02", nextRead.await().toHex())
  }

  /** Documents a known limit: Android callbacks carry no request id, so this case cannot be told apart. */
  @Test
  fun `a late result for the same characteristic completes the next read of that characteristic`() = runTest {
    connectSuccessfully(client, bluetooth)
    val timedOut = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }
    advanceTimeBy(timeouts.operation + 1.milliseconds)
    timedOut.await()
    val nextRead = startOperation { client.read(serviceUuid, firstUuid) }

    // the late answer to the read that timed out
    bluetooth.reportRead(first, "0a".hexToBytes())

    assertEquals("0a", nextRead.await().toHex())
  }

  @Test
  fun `a pending operation throws GattDisconnectedException when the link drops`() = runTest {
    connectSuccessfully(client, bluetooth)
    val read = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }

    bluetooth.reportDisconnected()

    val failure = read.await().exceptionOrNull()
    assertTrue(failure is GattDisconnectedException)
    assertEquals(LinkLossReason.REMOTE_DISCONNECTED, (failure as GattDisconnectedException).reason)
  }

  @Test
  fun `a pending operation throws GattDisconnectedException when Bluetooth is switched off`() = runTest {
    connectSuccessfully(client, bluetooth)
    val read = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }

    bluetooth.turnBluetoothOff()

    val failure = read.await().exceptionOrNull()
    assertTrue(failure is GattDisconnectedException)
    assertEquals(LinkLossReason.BLUETOOTH_TURNED_OFF, (failure as GattDisconnectedException).reason)
  }

  @Test
  fun `operations queued behind a dropped link also throw GattDisconnectedException`() = runTest {
    connectSuccessfully(client, bluetooth)
    val firstRead = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }
    val queuedRead = startOperation { runCatching { client.read(serviceUuid, secondUuid) } }

    bluetooth.reportDisconnected()

    assertTrue(firstRead.await().exceptionOrNull() is GattDisconnectedException)
    assertTrue(queuedRead.await().exceptionOrNull() is GattDisconnectedException)
  }

  @Test
  fun `a cancelled operation lets the next operation reach Android`() = runTest {
    connectSuccessfully(client, bluetooth)
    val cancelledRead = startOperation { client.read(serviceUuid, firstUuid) }
    cancelledRead.cancel()
    runCurrent()

    val nextRead = startOperation { client.read(serviceUuid, secondUuid) }
    bluetooth.reportRead(second, "02".hexToBytes())

    assertEquals("02", nextRead.await().toHex())
  }

  @Test
  fun `a pending operation throws GattDisconnectedException when the client is closed`() = runTest {
    connectSuccessfully(client, bluetooth)
    val read = startOperation { runCatching { client.read(serviceUuid, firstUuid) } }

    client.close()

    assertTrue(read.await().exceptionOrNull() is GattDisconnectedException)
  }
}
