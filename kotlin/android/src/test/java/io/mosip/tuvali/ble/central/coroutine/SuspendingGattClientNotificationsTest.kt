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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ISO 18013-5 clauses 8.3.3.1.1.4 and 8.3.3.1.1.5: the client enables notifications through the CCCD. */
@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingGattClientNotificationsTest {

  private val serviceUuid = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
  private val notifyUuid = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
  private val indicateUuid = UUID.fromString("0000a003-0000-1000-8000-00805f9b34fb")
  private val noCccdUuid = UUID.fromString("0000a004-0000-1000-8000-00805f9b34fb")
  private val readOnlyUuid = UUID.fromString("0000a005-0000-1000-8000-00805f9b34fb")

  private val bluetooth = MockAndroidBluetooth()
  private val client = SuspendingGattClient(bluetooth.context, GattTimeouts())

  private val service = bluetooth.addService(serviceUuid)
  private val notifying = bluetooth.addCharacteristic(service, notifyUuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
  private val indicating = bluetooth.addCharacteristic(service, indicateUuid, BluetoothGattCharacteristic.PROPERTY_INDICATE)
  private val withoutCccd = bluetooth.addCharacteristic(service, noCccdUuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY, withCccd = false)
  private val readOnly = bluetooth.addCharacteristic(service, readOnlyUuid, BluetoothGattCharacteristic.PROPERTY_READ)

  @Test
  fun `enableNotifications turns notifications on locally before writing the CCCD`() = runTest {
    connectSuccessfully(client, bluetooth)
    val enabling = startOperation { client.enableNotifications(serviceUuid, notifyUuid) }

    bluetooth.reportCccdWrite(notifying)
    enabling.await()

    verify { bluetooth.gatt.setCharacteristicNotification(notifying, true) }
  }

  @Test
  fun `enableNotifications writes 0x0001 to the CCCD of a notify characteristic`() = runTest {
    connectSuccessfully(client, bluetooth)
    val enabling = startOperation { client.enableNotifications(serviceUuid, notifyUuid) }

    bluetooth.reportCccdWrite(notifying)
    enabling.await()

    // 0x0001 as a little-endian 16-bit value
    assertEquals("0100", bluetooth.cccdValueOf(notifying)?.toHex())
  }

  @Test
  fun `enableNotifications writes 0x0002 to the CCCD of an indicate-only characteristic`() = runTest {
    connectSuccessfully(client, bluetooth)
    val enabling = startOperation { client.enableNotifications(serviceUuid, indicateUuid) }

    bluetooth.reportCccdWrite(indicating)
    enabling.await()

    // 0x0002 as a little-endian 16-bit value
    assertEquals("0200", bluetooth.cccdValueOf(indicating)?.toHex())
  }

  @Test
  fun `enableNotifications waits for Android to confirm the CCCD write before returning`() = runTest {
    connectSuccessfully(client, bluetooth)

    val enabling = startOperation { runCatching { client.enableNotifications(serviceUuid, notifyUuid) } }

    assertTrue(enabling.isActive)
  }

  @Test
  fun `enableNotifications throws GattOperationFailedException when Android reports a CCCD write error`() = runTest {
    connectSuccessfully(client, bluetooth)
    val enabling = startOperation { runCatching { client.enableNotifications(serviceUuid, notifyUuid) } }

    bluetooth.reportCccdWrite(notifying, status = BluetoothGatt.GATT_FAILURE)

    assertTrue(enabling.await().exceptionOrNull() is GattOperationFailedException)
  }

  @Test
  fun `enableNotifications throws GattAttributeNotFoundException when the characteristic has no CCCD`() = runTest {
    connectSuccessfully(client, bluetooth)

    val failure = runCatching { client.enableNotifications(serviceUuid, noCccdUuid) }.exceptionOrNull()

    assertTrue(failure is GattAttributeNotFoundException)
  }

  @Test
  fun `enableNotifications only turns notifications on locally when a missing CCCD is allowed`() = runTest {
    connectSuccessfully(client, bluetooth)

    client.enableNotifications(serviceUuid, noCccdUuid, allowMissingCccd = true)

    verify { bluetooth.gatt.setCharacteristicNotification(withoutCccd, true) }
    verify(exactly = 0) { bluetooth.gatt.writeDescriptor(any()) }
  }

  @Test
  fun `enableNotifications throws GattOperationFailedException when the characteristic cannot notify or indicate`() = runTest {
    connectSuccessfully(client, bluetooth)

    val failure = runCatching { client.enableNotifications(serviceUuid, readOnlyUuid) }.exceptionOrNull()

    assertTrue(failure is GattOperationFailedException)
    assertNull(bluetooth.cccdValueOf(readOnly))
  }

  @Test
  fun `enableNotifications throws GattOperationFailedException when Android refuses to enable notifications locally`() = runTest {
    connectSuccessfully(client, bluetooth)
    every { bluetooth.gatt.setCharacteristicNotification(any(), any()) } returns false

    val failure = runCatching { client.enableNotifications(serviceUuid, notifyUuid) }.exceptionOrNull()

    assertTrue(failure is GattOperationFailedException)
  }

  @Test
  fun `notifications delivers each notified value in the order Android delivered them`() = runTest {
    connectSuccessfully(client, bluetooth)
    bluetooth.reportNotification(notifying, "01".hexToBytes())
    bluetooth.reportNotification(notifying, "0203".hexToBytes())

    val received = client.notifications.take(2).toList()

    assertEquals(listOf(notifyUuid, notifyUuid), received.map { it.characteristicUuid })
    assertEquals(listOf("01", "0203"), received.map { it.value.toHex() })
  }

  @Test
  fun `notifications keeps its own copy of each value even if Android reuses the buffer`() = runTest {
    connectSuccessfully(client, bluetooth)
    val androidBuffer = "aa".hexToBytes()
    bluetooth.reportNotification(notifying, androidBuffer)

    androidBuffer[0] = 0x00
    val received = client.notifications.take(1).toList().single()

    assertEquals("aa", received.value.toHex())
  }

  @Test
  fun `disableNotifications writes 0x0000 to the CCCD and turns notifications off locally`() = runTest {
    connectSuccessfully(client, bluetooth)
    val disabling = startOperation { client.disableNotifications(serviceUuid, notifyUuid) }

    bluetooth.reportCccdWrite(notifying)
    disabling.await()

    assertEquals("0000", bluetooth.cccdValueOf(notifying)?.toHex())
    verify { bluetooth.gatt.setCharacteristicNotification(notifying, false) }
  }

  @Test
  fun `disableNotifications waits for Android to confirm the CCCD write before returning`() = runTest {
    connectSuccessfully(client, bluetooth)

    val disabling = startOperation { runCatching { client.disableNotifications(serviceUuid, notifyUuid) } }

    assertTrue(disabling.isActive)
  }

  @Test
  fun `disableNotifications throws GattOperationFailedException when Android reports a CCCD write error`() = runTest {
    connectSuccessfully(client, bluetooth)
    val disabling = startOperation { runCatching { client.disableNotifications(serviceUuid, notifyUuid) } }

    bluetooth.reportCccdWrite(notifying, status = BluetoothGatt.GATT_FAILURE)

    assertTrue(disabling.await().exceptionOrNull() is GattOperationFailedException)
  }

  @Test
  fun `disableNotifications only turns notifications off locally when a missing CCCD is allowed`() = runTest {
    connectSuccessfully(client, bluetooth)

    client.disableNotifications(serviceUuid, noCccdUuid, allowMissingCccd = true)

    verify { bluetooth.gatt.setCharacteristicNotification(withoutCccd, false) }
    verify(exactly = 0) { bluetooth.gatt.writeDescriptor(any()) }
  }

  @Test
  fun `notifications delivers values received before a link drop, then throws GattDisconnectedException`() = runTest {
    connectSuccessfully(client, bluetooth)
    bluetooth.reportNotification(notifying, "01".hexToBytes())

    bluetooth.reportDisconnected()

    val received = mutableListOf<String>()
    val failure = runCatching { client.notifications.collect { received += it.value.toHex() } }.exceptionOrNull()
    assertEquals(listOf("01"), received)
    assertTrue(failure is GattDisconnectedException)
  }

  @Test
  fun `notifications ends normally after close`() = runTest {
    connectSuccessfully(client, bluetooth)
    bluetooth.reportNotification(notifying, "01".hexToBytes())

    client.close()

    assertEquals(listOf("01"), client.notifications.toList().map { it.value.toHex() })
  }
}
