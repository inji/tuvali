package io.mosip.tuvali.testutils.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mosip.tuvali.ble.central.coroutine.CCCD_UUID
import java.util.UUID

/**
 * A mock of the wallet phone's Android Bluetooth stack. It holds the remote server's GATT
 * table only as data (what Android would show after discovery); it has no reader behaviour.
 *
 * Production code talks to mocked [BluetoothGatt], [BluetoothDevice] and characteristic
 * objects; the test then plays Android's role by calling the captured [BluetoothGattCallback]
 * with `report…` functions, exactly as Android would on its Bluetooth threads.
 */
class MockAndroidBluetooth {

  val context: Context = mockk(relaxed = true)
  val device: BluetoothDevice = mockk(relaxed = true)
  val gatt: BluetoothGatt = mockk(relaxed = true)

  /** Set to null to simulate connectGatt failing to start. */
  var gattReturnedByConnect: BluetoothGatt? = gatt

  lateinit var callback: BluetoothGattCallback
    private set

  private val services = mutableListOf<BluetoothGattService>()
  private val registeredReceivers = mutableListOf<BroadcastReceiver>()
  private val characteristicValues = mutableMapOf<BluetoothGattCharacteristic, ByteArray?>()
  private val descriptorValues = mutableMapOf<BluetoothGattDescriptor, ByteArray?>()
  private val characteristicWriteTypes = mutableMapOf<BluetoothGattCharacteristic, Int>()

  init {
    every { device.connectGatt(any<Context>(), any<Boolean>(), any<BluetoothGattCallback>(), any<Int>()) } answers {
      callback = thirdArg()
      gattReturnedByConnect
    }
    every { context.registerReceiver(any(), any()) } answers {
      registeredReceivers += firstArg<BroadcastReceiver>()
      null
    }
    every { context.unregisterReceiver(any()) } answers {
      registeredReceivers -= firstArg<BroadcastReceiver>()
    }
    every { gatt.services } answers { services.toList() }
    every { gatt.getService(any()) } answers { services.firstOrNull { it.uuid == firstArg() } }
    every { gatt.discoverServices() } returns true
    every { gatt.requestMtu(any()) } returns true
    every { gatt.readCharacteristic(any()) } returns true
    every { gatt.writeCharacteristic(any()) } returns true
    every { gatt.writeDescriptor(any()) } returns true
    every { gatt.setCharacteristicNotification(any(), any()) } returns true
  }

  val hasRegisteredReceiver: Boolean get() = registeredReceivers.isNotEmpty()

  fun addService(serviceUuid: UUID): BluetoothGattService {
    val service = mockk<BluetoothGattService>(relaxed = true)
    val characteristics = mutableListOf<BluetoothGattCharacteristic>()
    every { service.uuid } returns serviceUuid
    every { service.characteristics } answers { characteristics.toList() }
    every { service.getCharacteristic(any()) } answers { characteristics.firstOrNull { it.uuid == firstArg() } }
    every { service.addCharacteristic(any()) } answers {
      characteristics += firstArg<BluetoothGattCharacteristic>()
      true
    }
    services += service
    return service
  }

  fun addCharacteristic(
    service: BluetoothGattService,
    characteristicUuid: UUID,
    properties: Int,
    withCccd: Boolean = true,
  ): BluetoothGattCharacteristic {
    val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
    every { characteristic.uuid } returns characteristicUuid
    every { characteristic.properties } returns properties
    every { characteristic.service } returns service
    every { characteristic.value } answers { characteristicValues[characteristic] }
    every { characteristic.setValue(any<ByteArray>()) } answers {
      characteristicValues[characteristic] = firstArg()
      true
    }
    every { characteristic.writeType } answers { characteristicWriteTypes[characteristic] ?: 0 }
    every { characteristic.writeType = any() } answers { characteristicWriteTypes[characteristic] = firstArg() }

    val cccd = if (withCccd) {
      mockk<BluetoothGattDescriptor>(relaxed = true).also { descriptor ->
        every { descriptor.uuid } returns CCCD_UUID
        every { descriptor.characteristic } returns characteristic
        every { descriptor.value } answers { descriptorValues[descriptor] }
        every { descriptor.setValue(any()) } answers {
          descriptorValues[descriptor] = firstArg()
          true
        }
      }
    } else {
      null
    }
    every { characteristic.getDescriptor(CCCD_UUID) } returns cccd
    service.addCharacteristic(characteristic)
    return characteristic
  }

  fun valueWrittenTo(characteristic: BluetoothGattCharacteristic): ByteArray? = characteristicValues[characteristic]

  fun writeTypeOf(characteristic: BluetoothGattCharacteristic): Int? = characteristicWriteTypes[characteristic]

  fun cccdValueOf(characteristic: BluetoothGattCharacteristic): ByteArray? =
    characteristic.getDescriptor(CCCD_UUID)?.let { descriptorValues[it] }

  // ---------- Android's side: callbacks ----------

  fun reportConnected(status: Int = BluetoothGatt.GATT_SUCCESS) =
    callback.onConnectionStateChange(gatt, status, BluetoothProfile.STATE_CONNECTED)

  fun reportDisconnected(status: Int = BluetoothGatt.GATT_SUCCESS) =
    callback.onConnectionStateChange(gatt, status, BluetoothProfile.STATE_DISCONNECTED)

  fun reportServicesDiscovered(status: Int = BluetoothGatt.GATT_SUCCESS) =
    callback.onServicesDiscovered(gatt, status)

  fun reportMtuChanged(mtu: Int, status: Int = BluetoothGatt.GATT_SUCCESS) =
    callback.onMtuChanged(gatt, mtu, status)

  fun reportRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int = BluetoothGatt.GATT_SUCCESS) {
    characteristicValues[characteristic] = value
    callback.onCharacteristicRead(gatt, characteristic, status)
  }

  fun reportWrite(characteristic: BluetoothGattCharacteristic, status: Int = BluetoothGatt.GATT_SUCCESS) =
    callback.onCharacteristicWrite(gatt, characteristic, status)

  fun reportCccdWrite(characteristic: BluetoothGattCharacteristic, status: Int = BluetoothGatt.GATT_SUCCESS) =
    callback.onDescriptorWrite(gatt, characteristic.getDescriptor(CCCD_UUID), status)

  fun reportNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
    characteristicValues[characteristic] = value
    callback.onCharacteristicChanged(gatt, characteristic)
  }

  fun turnBluetoothOff() {
    val intent = mockk<Intent>(relaxed = true)
    every { intent.action } returns BluetoothAdapter.ACTION_STATE_CHANGED
    every { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } returns null
    every { intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, any()) } returns BluetoothAdapter.STATE_OFF
    every { intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, any()) } returns BluetoothAdapter.STATE_ON
    registeredReceivers.toList().forEach { it.onReceive(context, intent) }
  }
}
