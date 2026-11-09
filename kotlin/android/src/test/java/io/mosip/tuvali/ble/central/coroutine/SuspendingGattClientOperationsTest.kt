package io.mosip.tuvali.ble.central.coroutine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import io.mockk.every
import io.mockk.verify
import io.mosip.tuvali.iso.exception.GattAttributeNotFoundException
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.iso.exception.GattOperationFailedException
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

@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingGattClientOperationsTest {

  private val serviceUuid = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
  private val readableUuid = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
  private val writableUuid = UUID.fromString("0000a003-0000-1000-8000-00805f9b34fb")
  private val missingUuid = UUID.fromString("0000a0ff-0000-1000-8000-00805f9b34fb")

  private val bluetooth = MockAndroidBluetooth()
  private val timeouts = GattTimeouts(connect = 10.seconds, operation = 5.seconds)
  private val client = SuspendingGattClient(bluetooth.context, timeouts)

  private val service = bluetooth.addService(serviceUuid)
  private val readable = bluetooth.addCharacteristic(service, readableUuid, BluetoothGattCharacteristic.PROPERTY_READ)
  private val writable = bluetooth.addCharacteristic(service, writableUuid, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

  // ---------- service discovery ----------

  @Test
  fun `discoverServices returns the services Android discovered`() = runTest {
    connectSuccessfully(client, bluetooth)
    val discovering = startOperation { client.discoverServices() }

    bluetooth.reportServicesDiscovered()

    assertEquals(listOf(serviceUuid), discovering.await().map { it.uuid })
  }

  @Test
  fun `discoverServices throws GattOperationFailedException when Android reports a discovery error`() = runTest {
    connectSuccessfully(client, bluetooth)
    val discovering = startOperation { runCatching { client.discoverServices() } }

    bluetooth.reportServicesDiscovered(status = BluetoothGatt.GATT_FAILURE)

    assertTrue(discovering.await().exceptionOrNull() is GattOperationFailedException)
  }

  @Test
  fun `findService retries discovery until the service appears`() = runTest {
    connectSuccessfully(client, bluetooth)
    val laterServiceUuid = UUID.fromString("0000b001-0000-1000-8000-00805f9b34fb")
    val finding = startOperation { client.findService(laterServiceUuid, attempts = 3, retryDelay = 500.milliseconds) }
    bluetooth.reportServicesDiscovered()
    advanceTimeBy(501.milliseconds)
    runCurrent()

    bluetooth.addService(laterServiceUuid)
    bluetooth.reportServicesDiscovered()

    assertEquals(laterServiceUuid, finding.await().uuid)
  }

  @Test
  fun `findService throws GattAttributeNotFoundException when the service is still missing after all attempts`() = runTest {
    connectSuccessfully(client, bluetooth)
    val finding = startOperation { runCatching { client.findService(missingUuid, attempts = 2, retryDelay = 500.milliseconds) } }
    bluetooth.reportServicesDiscovered()
    advanceTimeBy(501.milliseconds)
    runCurrent()

    bluetooth.reportServicesDiscovered()

    assertTrue(finding.await().exceptionOrNull() is GattAttributeNotFoundException)
  }

  // ---------- MTU ----------

  @Test
  fun `requestMtu asks Android for the largest ATT MTU by default`() = runTest {
    connectSuccessfully(client, bluetooth)
    val requesting = startOperation { client.requestMtu() }

    bluetooth.reportMtuChanged(MAX_ATT_MTU)
    requesting.await()

    verify { bluetooth.gatt.requestMtu(MAX_ATT_MTU) }
  }

  @Test
  fun `requestMtu returns whatever MTU Android negotiated, even if smaller than asked`() = runTest {
    connectSuccessfully(client, bluetooth)
    val requesting = startOperation { client.requestMtu() }

    bluetooth.reportMtuChanged(185)

    assertEquals(185, requesting.await())
  }

  @Test
  fun `requestMtu caps the result so a chunk of MTU minus 3 bytes fits in a 512-byte attribute value`() = runTest {
    connectSuccessfully(client, bluetooth)
    val requesting = startOperation { client.requestMtu() }

    bluetooth.reportMtuChanged(MAX_ATT_MTU)

    assertEquals(515, requesting.await())
  }

  @Test
  fun `requestMtu falls back to the default ATT MTU when Android reports a failure`() = runTest {
    connectSuccessfully(client, bluetooth)
    val requesting = startOperation { client.requestMtu() }

    bluetooth.reportMtuChanged(MAX_ATT_MTU, status = BluetoothGatt.GATT_FAILURE)

    assertEquals(DEFAULT_ATT_MTU, requesting.await())
  }

  @Test
  fun `requestMtu falls back to the default ATT MTU when Android refuses to start the request`() = runTest {
    connectSuccessfully(client, bluetooth)
    every { bluetooth.gatt.requestMtu(any()) } returns false

    val mtu = client.requestMtu()

    assertEquals(DEFAULT_ATT_MTU, mtu)
  }

  @Test
  fun `requestMtu falls back to the default ATT MTU when Android never answers`() = runTest {
    connectSuccessfully(client, bluetooth)
    val requesting = startOperation { client.requestMtu() }

    advanceTimeBy(timeouts.operation + 1.milliseconds)

    assertEquals(DEFAULT_ATT_MTU, requesting.await())
  }

  @Test
  fun `requestMtu throws GattDisconnectedException instead of falling back when the link drops`() = runTest {
    connectSuccessfully(client, bluetooth)
    val requesting = startOperation { runCatching { client.requestMtu() } }

    bluetooth.reportDisconnected()

    assertTrue(requesting.await().exceptionOrNull() is GattDisconnectedException)
  }

  // ---------- read ----------

  @Test
  fun `read returns the characteristic value Android delivered`() = runTest {
    connectSuccessfully(client, bluetooth)
    val reading = startOperation { client.read(serviceUuid, readableUuid) }

    bluetooth.reportRead(readable, "0a0b0c".hexToBytes())

    assertEquals("0a0b0c", reading.await().toHex())
  }

  @Test
  fun `read throws GattOperationFailedException when Android reports a read error`() = runTest {
    connectSuccessfully(client, bluetooth)
    val reading = startOperation { runCatching { client.read(serviceUuid, readableUuid) } }

    bluetooth.reportRead(readable, ByteArray(0), status = BluetoothGatt.GATT_FAILURE)

    assertTrue(reading.await().exceptionOrNull() is GattOperationFailedException)
  }

  @Test
  fun `read throws GattAttributeNotFoundException when the characteristic does not exist`() = runTest {
    connectSuccessfully(client, bluetooth)

    val failure = runCatching { client.read(serviceUuid, missingUuid) }.exceptionOrNull()

    assertTrue(failure is GattAttributeNotFoundException)
  }

  @Test
  fun `read throws GattAttributeNotFoundException when the service does not exist`() = runTest {
    connectSuccessfully(client, bluetooth)

    val failure = runCatching { client.read(missingUuid, readableUuid) }.exceptionOrNull()

    assertTrue(failure is GattAttributeNotFoundException)
  }

  // ---------- write without response ----------

  @Test
  fun `writeWithoutResponse sends the value with the no-response write type`() = runTest {
    connectSuccessfully(client, bluetooth)
    val writing = startOperation { client.writeWithoutResponse(serviceUuid, writableUuid, "01".hexToBytes()) }

    bluetooth.reportWrite(writable)
    writing.await()

    assertEquals("01", bluetooth.valueWrittenTo(writable)?.toHex())
    assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, bluetooth.writeTypeOf(writable))
  }

  @Test
  fun `writeWithoutResponse waits for Android to accept the write before returning`() = runTest {
    connectSuccessfully(client, bluetooth)

    val writing = startOperation { runCatching { client.writeWithoutResponse(serviceUuid, writableUuid, "01".hexToBytes()) } }

    assertTrue(writing.isActive)
  }

  @Test
  fun `writeWithoutResponse throws GattOperationFailedException when Android reports a write error`() = runTest {
    connectSuccessfully(client, bluetooth)
    val writing = startOperation { runCatching { client.writeWithoutResponse(serviceUuid, writableUuid, "01".hexToBytes()) } }

    bluetooth.reportWrite(writable, status = BluetoothGatt.GATT_FAILURE)

    assertTrue(writing.await().exceptionOrNull() is GattOperationFailedException)
  }

  @Test
  fun `writeWithoutResponse throws GattOperationFailedException when Android refuses to start the write`() = runTest {
    connectSuccessfully(client, bluetooth)
    every { bluetooth.gatt.writeCharacteristic(any()) } returns false

    val failure = runCatching { client.writeWithoutResponse(serviceUuid, writableUuid, "01".hexToBytes()) }.exceptionOrNull()

    assertTrue(failure is GattOperationFailedException)
  }
}
