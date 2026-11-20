package io.mosip.tuvali.testutils.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor

/**
 * A mock of Android's Bluetooth LE scanning, plugged into an existing mocked [context]
 * (normally [MockAndroidBluetooth.context]). The test plays Android with `report…` functions.
 *
 * Android's scan filter and settings builders are stubs in JVM unit tests (they return null),
 * so their constructors are mocked to return working builders.
 */
class MockBleScanning(context: Context) {

  val adapter: BluetoothAdapter = mockk(relaxed = true)
  val leScanner: BluetoothLeScanner = mockk(relaxed = true)

  var bluetoothEnabled = true
  var scanCallback: ScanCallback? = null
    private set
  var filtersPassed: List<ScanFilter>? = null
    private set
  private var stopped = false

  val isScanning: Boolean get() = scanCallback != null && !stopped

  init {
    val manager = mockk<BluetoothManager>(relaxed = true)
    every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
    every { manager.adapter } returns adapter
    every { adapter.isEnabled } answers { bluetoothEnabled }
    every { adapter.bluetoothLeScanner } answers { if (bluetoothEnabled) leScanner else null }
    every { leScanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>()) } answers {
      filtersPassed = firstArg()
      scanCallback = thirdArg()
    }
    every { leScanner.stopScan(any<ScanCallback>()) } answers { stopped = true }

    mockkConstructor(ScanFilter.Builder::class, ScanSettings.Builder::class)
    every { anyConstructed<ScanFilter.Builder>().setServiceUuid(any()) } answers { self as ScanFilter.Builder }
    every { anyConstructed<ScanFilter.Builder>().build() } returns mockk(relaxed = true)
    every { anyConstructed<ScanSettings.Builder>().setScanMode(any()) } answers { self as ScanSettings.Builder }
    every { anyConstructed<ScanSettings.Builder>().build() } returns mockk(relaxed = true)
  }

  fun device(address: String): BluetoothDevice = mockk(relaxed = true) {
    every { this@mockk.address } returns address
  }

  fun reportFound(device: BluetoothDevice) {
    val result = mockk<ScanResult>(relaxed = true) { every { this@mockk.device } returns device }
    checkNotNull(scanCallback) { "Scan not started" }.onScanResult(1, result)
  }

  fun reportScanFailed(errorCode: Int) {
    checkNotNull(scanCallback) { "Scan not started" }.onScanFailed(errorCode)
  }
}
