package io.mosip.tuvali.iso.transport.ble

import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.iso.exception.BleScanFailedException
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.testutils.ble.MockAndroidBluetooth
import io.mosip.tuvali.testutils.ble.MockBleScanning
import io.mosip.tuvali.testutils.ble.startOperation
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Scanning for the reader that advertises the session UUID: ISO 18013-5 clause 8.3.3.1.1.3. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleReaderFinderTest {

  private val sessionUuid = UUID.fromString("45efef74-2b2c-4837-a9a3-b0e1d05a6917")
  private val bluetooth = MockAndroidBluetooth()
  private val scanning = MockBleScanning(bluetooth.context)
  private val finder = BleReaderFinder(bluetooth.context)

  @Test
  fun `findReader scans with one filter for the session service UUID`() = runTest {
    val finding = startOperation { finder.findReader(sessionUuid, emptySet()) }

    val filters = scanning.filtersPassed
    finding.cancel()

    assertEquals(1, filters?.size)
  }

  @Test
  fun `findReader returns the first reader the scan finds`() = runTest {
    val reader = scanning.device("AA:AA:AA:AA:AA:AA")
    val finding = startOperation { finder.findReader(sessionUuid, emptySet()) }

    scanning.reportFound(reader)

    assertSame(reader, finding.await())
  }

  @Test
  fun `findReader skips excluded readers and returns the next one`() = runTest {
    // a reader that already failed the Ident check in this session
    val wrongReader = scanning.device("AA:AA:AA:AA:AA:AA")
    val rightReader = scanning.device("BB:BB:BB:BB:BB:BB")
    val finding = startOperation { finder.findReader(sessionUuid, setOf("AA:AA:AA:AA:AA:AA")) }

    scanning.reportFound(wrongReader)
    scanning.reportFound(rightReader)

    assertSame(rightReader, finding.await())
  }

  @Test
  fun `findReader stops scanning once it finds a reader`() = runTest {
    val finding = startOperation { finder.findReader(sessionUuid, emptySet()) }

    scanning.reportFound(scanning.device("AA:AA:AA:AA:AA:AA"))
    finding.await()

    assertFalse(scanning.isScanning)
  }

  @Test
  fun `findReader throws GattDisconnectedException without scanning when Bluetooth is off`() = runTest {
    scanning.bluetoothEnabled = false

    val failure = runCatching { finder.findReader(sessionUuid, emptySet()) }.exceptionOrNull()

    assertEquals(LinkLossReason.BLUETOOTH_TURNED_OFF, (failure as GattDisconnectedException).reason)
    assertNull(scanning.scanCallback)
  }

  @Test
  fun `findReader throws GattDisconnectedException when Bluetooth is switched off while scanning`() = runTest {
    val finding = startOperation { runCatching { finder.findReader(sessionUuid, emptySet()) } }

    bluetooth.turnBluetoothOff()

    val failure = finding.await().exceptionOrNull()
    assertEquals(LinkLossReason.BLUETOOTH_TURNED_OFF, (failure as GattDisconnectedException).reason)
  }

  @Test
  fun `findReader throws BleScanFailedException when Android cannot start the scan`() = runTest {
    // ScanCallback.SCAN_FAILED_INTERNAL_ERROR
    val internalError = 3
    val finding = startOperation { runCatching { finder.findReader(sessionUuid, emptySet()) } }

    scanning.reportScanFailed(internalError)

    val failure = finding.await().exceptionOrNull()
    assertEquals(internalError, (failure as BleScanFailedException).errorCode)
  }

  @Test
  fun `findReader stops scanning and stops listening for Bluetooth changes when cancelled`() = runTest {
    val finding = startOperation { finder.findReader(sessionUuid, emptySet()) }

    finding.cancel()
    runCurrent()

    assertFalse(scanning.isScanning)
    assertFalse(bluetooth.hasRegisteredReceiver)
  }

  @Test
  fun `findReader stops listening for Bluetooth changes once it finds a reader`() = runTest {
    val finding = startOperation { finder.findReader(sessionUuid, emptySet()) }

    scanning.reportFound(scanning.device("AA:AA:AA:AA:AA:AA"))
    finding.await()

    assertFalse(bluetooth.hasRegisteredReceiver)
  }
}
