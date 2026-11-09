package io.mosip.tuvali.iso.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.ble.central.impl.Scanner
import io.mosip.tuvali.iso.exception.BleScanFailedException
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.common.BluetoothStateChangeReceiver
import io.mosip.tuvali.transfer.Util.Companion.getLogTag
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine

/** Finds a reader advertising the session service UUID (clause 8.3.3.1.1.3). */
internal fun interface ReaderFinder {
  /** Scans until a reader whose address is not in [excludedAddresses] advertises [serviceUuid]. */
  suspend fun findReader(serviceUuid: UUID, excludedAddresses: Set<String>): BluetoothDevice
}

/**
 * [ReaderFinder] on Tuvali's existing [Scanner]. It fails at once if Bluetooth is off or is
 * switched off while scanning, and always stops the scan when it finishes or is cancelled.
 */
internal class BleReaderFinder(private val context: Context) : ReaderFinder {
  private val logTag = getLogTag(javaClass.simpleName)

  override suspend fun findReader(serviceUuid: UUID, excludedAddresses: Set<String>): BluetoothDevice {
    // Scanner reads the LE scanner when created, which is null while Bluetooth is off
    if (!isBluetoothEnabled()) throw GattDisconnectedException(LinkLossReason.BLUETOOTH_TURNED_OFF)
    val scanner = Scanner(context)

    return suspendCancellableCoroutine { continuation ->
      val finished = AtomicBoolean(false)
      var bluetoothStateReceiver: BluetoothStateChangeReceiver? = null

      fun stopEverything() {
        runCatching { scanner.stopScan() }.onFailure { Log.w(logTag, "Could not stop scan: ${it.message}") }
        bluetoothStateReceiver?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
      }

      fun finish(result: Result<BluetoothDevice>) {
        if (!finished.compareAndSet(false, true)) return
        stopEverything()
        continuation.resumeWith(result)
      }

      continuation.invokeOnCancellation {
        if (finished.compareAndSet(false, true)) stopEverything()
      }

      bluetoothStateReceiver = BluetoothStateChangeReceiver { _, _ ->
        finish(Result.failure(GattDisconnectedException(LinkLossReason.BLUETOOTH_TURNED_OFF)))
      }.also { receiver -> context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }

      try {
        scanner.start(
          serviceUuid,
          onDeviceFound = { result ->
            val device = result.device
            if (device != null && device.address !in excludedAddresses) finish(Result.success(device))
          },
          onScanStartFailure = { errorCode -> finish(Result.failure(BleScanFailedException(errorCode))) },
        )
      } catch (exception: SecurityException) {
        Log.e(logTag, "Scan not permitted: ${exception.message}")
        finish(Result.failure(exception))
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun isBluetoothEnabled(): Boolean {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter?.isEnabled == true
  }
}
