package io.mosip.tuvali.ble.central.coroutine

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import io.mosip.tuvali.iso.exception.GattAttributeNotFoundException
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.iso.exception.GattOperationFailedException
import io.mosip.tuvali.iso.exception.GattOperationTimeoutException
import io.mosip.tuvali.common.BluetoothStateChangeReceiver
import io.mosip.tuvali.transfer.Util.Companion.getLogTag
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// CCCD values are 16-bit little-endian: 0x0001 enables notifications, 0x0002 indications
private val CCCD_ENABLE_NOTIFICATIONS = byteArrayOf(0x01, 0x00)
private val CCCD_ENABLE_INDICATIONS = byteArrayOf(0x02, 0x00)
private val CCCD_DISABLE = byteArrayOf(0x00, 0x00)

/**
 * A Bluetooth LE GATT client where each operation suspends until Android's callback arrives.
 *
 * Android runs only one GATT operation at a time per connection, so operations are serialized
 * with a [Mutex]: a second call waits until the first has its answer, or times out. If the link
 * drops or Bluetooth is switched off, the pending operation and every queued one fail with
 * [GattDisconnectedException].
 *
 * Android's callbacks carry no request id, only the operation and the characteristic. A late
 * callback for an operation that already timed out is ignored, unless the operation now waiting
 * is the same kind on the same characteristic: then the late answer completes it. So after a
 * [GattOperationTimeoutException], treat the connection as
 * unreliable and close it rather than retrying on it.
 *
 * One instance serves one connection; create a new one per session.
 */
@SuppressLint("MissingPermission")
internal class SuspendingGattClient(
  private val context: Context,
  private val timeouts: GattTimeouts = GattTimeouts(),
) {
  private val logTag = getLogTag(javaClass.simpleName)

  private val mutableConnectionState = MutableStateFlow<GattConnectionState>(GattConnectionState.Disconnected)
  val connectionState: StateFlow<GattConnectionState> = mutableConnectionState.asStateFlow()

  private val notificationChannel = Channel<GattNotification>(Channel.UNLIMITED)

  /** Values pushed by the remote server, in arrival order. Meant for a single collector. */
  val notifications: Flow<GattNotification> = notificationChannel.receiveAsFlow()

  private val operationMutex = Mutex()
  private val pendingOperationLock = Any()
  private var pendingOperation: PendingOperation? = null

  @Volatile
  private var gatt: BluetoothGatt? = null
  private var bluetoothStateReceiver: BluetoothStateChangeReceiver? = null

  private class PendingOperation(
    val operation: GattOperation,
    val characteristicUuid: UUID?,
    val continuation: CancellableContinuation<Any?>,
  )

  @TargetApi(Build.VERSION_CODES.M)
  suspend fun connect(device: BluetoothDevice) {
    check(gatt == null && connectionState.value != GattConnectionState.Connecting) { "GATT client is already connected" }
    mutableConnectionState.value = GattConnectionState.Connecting
    registerBluetoothStateReceiver()
    try {
      runOperation<Unit>(GattOperation.CONNECT, null, timeouts.connect, requiresConnection = false) {
        val openedGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt = openedGatt
        openedGatt != null
      }
    } catch (exception: Exception) {
      releaseConnection()
      mutableConnectionState.value = GattConnectionState.Disconnected
      throw exception
    }
    mutableConnectionState.value = GattConnectionState.Connected
    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
  }

  suspend fun discoverServices(): List<BluetoothGattService> =
    runOperation(GattOperation.DISCOVER_SERVICES, null, timeouts.operation) {
      requireGatt().discoverServices()
    }

  /**
   * Discovers services until [serviceUuid] appears. Android sometimes returns an incomplete
   * list right after connecting, so discovery is retried [attempts] times.
   */
  suspend fun findService(serviceUuid: UUID, attempts: Int = 3, retryDelay: Duration = 500.milliseconds): BluetoothGattService {
    repeat(attempts) { attempt ->
      discoverServices().firstOrNull { service -> service.uuid == serviceUuid }?.let { service -> return service }
      if (attempt < attempts - 1) delay(retryDelay)
    }
    throw GattAttributeNotFoundException("Service $serviceUuid")
  }

  /**
   * Asks for [desiredMtu] and returns the ATT MTU Android negotiated, capped at
   * [MAX_USABLE_ATT_MTU] so every chunk fits in one attribute value.
   * Falls back to [DEFAULT_ATT_MTU] if negotiation fails, so a share can still go ahead.
   */
  suspend fun requestMtu(desiredMtu: Int = MAX_ATT_MTU): Int = try {
    runOperation<Int>(GattOperation.REQUEST_MTU, null, timeouts.operation) {
      requireGatt().requestMtu(desiredMtu)
    }.coerceIn(DEFAULT_ATT_MTU, MAX_USABLE_ATT_MTU)
  } catch (failure: GattOperationFailedException) {
    Log.w(logTag, "MTU negotiation failed, using the default: ${failure.message}")
    DEFAULT_ATT_MTU
  } catch (timeout: GattOperationTimeoutException) {
    Log.w(logTag, "MTU negotiation timed out, using the default")
    DEFAULT_ATT_MTU
  }

  suspend fun read(serviceUuid: UUID, characteristicUuid: UUID): ByteArray {
    val characteristic = findCharacteristic(serviceUuid, characteristicUuid)
    return runOperation(GattOperation.READ_CHARACTERISTIC, characteristicUuid, timeouts.operation) {
      requireGatt().readCharacteristic(characteristic)
    }
  }

  /** Writes without a response from the server; returns once Android has accepted the write. */
  suspend fun writeWithoutResponse(serviceUuid: UUID, characteristicUuid: UUID, value: ByteArray) {
    val characteristic = findCharacteristic(serviceUuid, characteristicUuid)
    runOperation<Unit>(GattOperation.WRITE_CHARACTERISTIC, characteristicUuid, timeouts.operation) {
      characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      characteristic.value = value
      requireGatt().writeCharacteristic(characteristic)
    }
  }

  /**
   * Turns on notifications (or indications) by enabling them in Android and writing the
   * characteristic's CCCD. Some servers (e.g. Tuvali's own verifier) have no CCCD and push
   * values anyway; [allowMissingCccd] keeps working with them.
   */
  suspend fun enableNotifications(serviceUuid: UUID, characteristicUuid: UUID, allowMissingCccd: Boolean = false) {
    val characteristic = findCharacteristic(serviceUuid, characteristicUuid)
    val cccdValue = when {
      characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> CCCD_ENABLE_NOTIFICATIONS
      characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> CCCD_ENABLE_INDICATIONS
      else -> throw GattOperationFailedException(
        GattOperation.ENABLE_NOTIFICATIONS,
        "$characteristicUuid cannot notify or indicate"
      )
    }
    val cccd: BluetoothGattDescriptor? = characteristic.getDescriptor(CCCD_UUID)

    if (cccd == null) {
      if (!allowMissingCccd) throw GattAttributeNotFoundException("CCCD of $characteristicUuid")
      operationMutex.withLock {
        ensureConnected()
        if (!requireGatt().setCharacteristicNotification(characteristic, true)) {
          throw GattOperationFailedException(
            GattOperation.ENABLE_NOTIFICATIONS,
            "Android refused to enable notifications"
          )
        }
      }
      return
    }

    runOperation<Unit>(GattOperation.ENABLE_NOTIFICATIONS, characteristicUuid, timeouts.operation) {
      val currentGatt = requireGatt()
      currentGatt.setCharacteristicNotification(characteristic, true) && run {
        cccd.value = cccdValue
        currentGatt.writeDescriptor(cccd)
      }
    }
  }

  /**
   * Turns notifications off: writes 0x0000 to the CCCD, then stops Android delivering them.
   * ISO 18013-5 clause 8.3.3.1.1.7 asks the client to unsubscribe before disconnecting.
   */
  suspend fun disableNotifications(serviceUuid: UUID, characteristicUuid: UUID, allowMissingCccd: Boolean = false) {
    val characteristic = findCharacteristic(serviceUuid, characteristicUuid)
    val cccd: BluetoothGattDescriptor? = characteristic.getDescriptor(CCCD_UUID)

    if (cccd == null) {
      if (!allowMissingCccd) throw GattAttributeNotFoundException("CCCD of $characteristicUuid")
      operationMutex.withLock {
        ensureConnected()
        requireGatt().setCharacteristicNotification(characteristic, false)
      }
      return
    }

    runOperation<Unit>(GattOperation.DISABLE_NOTIFICATIONS, characteristicUuid, timeouts.operation) {
      cccd.value = CCCD_DISABLE
      requireGatt().writeDescriptor(cccd)
    }
    operationMutex.withLock { gatt?.setCharacteristicNotification(characteristic, false) }
  }

  /** Disconnects and releases everything. Pending and later operations fail. */
  fun close() {
    mutableConnectionState.value = GattConnectionState.Disconnected
    failPendingOperation(GattDisconnectedException(null))
    gatt?.disconnect()
    releaseConnection()
    notificationChannel.close()
  }

  // ---------- operation plumbing ----------

  /**
   * Runs one GATT operation: waits for its turn, asks Android to [start] it, then suspends
   * until the matching callback (or the timeout). [start] returns false if Android refuses.
   */
  private suspend fun <T> runOperation(
    operation: GattOperation,
    characteristicUuid: UUID?,
    timeout: Duration,
    requiresConnection: Boolean = true,
    start: () -> Boolean,
  ): T = operationMutex.withLock {
    if (requiresConnection) ensureConnected()
    try {
      @Suppress("UNCHECKED_CAST")
      withTimeout(timeout) {
        suspendCancellableCoroutine<Any?> { continuation ->
          val pending = PendingOperation(operation, characteristicUuid, continuation)
          synchronized(pendingOperationLock) { pendingOperation = pending }
          continuation.invokeOnCancellation { clearIfPending(pending) }

          val started = try {
            start()
          } catch (exception: SecurityException) {
            Log.e(logTag, "$operation not permitted: ${exception.message}")
            false
          }
          if (!started && clearIfPending(pending)) {
            continuation.resumeWithException(
              GattOperationFailedException(
                operation,
                "Android refused to start it"
              )
            )
          }
        }
      } as T
    } catch (exception: TimeoutCancellationException) {
      throw GattOperationTimeoutException(operation, timeout)
    }
  }

  private fun clearIfPending(pending: PendingOperation): Boolean = synchronized(pendingOperationLock) {
    if (pendingOperation === pending) {
      pendingOperation = null
      true
    } else {
      false
    }
  }

  /** Completes the pending operation if it is the one this callback answers. */
  private fun completePendingOperation(operation: GattOperation, characteristicUuid: UUID?, result: Result<Any?>) {
    val pending = synchronized(pendingOperationLock) {
      val current = pendingOperation ?: return
      if (current.operation != operation) return
      if (current.characteristicUuid != null && current.characteristicUuid != characteristicUuid) return
      pendingOperation = null
      current
    }
    pending.continuation.resumeWith(result)
  }

  /** Which CCCD write (enable or disable) is waiting, if any. */
  private fun pendingCccdOperation(): GattOperation? = synchronized(pendingOperationLock) {
    pendingOperation?.operation?.takeIf {
      it == GattOperation.ENABLE_NOTIFICATIONS || it == GattOperation.DISABLE_NOTIFICATIONS
    }
  }

  private fun failPendingOperation(failure: Exception) {
    val pending = synchronized(pendingOperationLock) {
      pendingOperation.also { pendingOperation = null }
    }
    pending?.continuation?.resumeWithException(failure)
  }

  private fun ensureConnected() {
    when (val state = connectionState.value) {
      GattConnectionState.Connected -> Unit
      is GattConnectionState.Lost -> throw GattDisconnectedException(state.reason)
      else -> throw GattDisconnectedException(null)
    }
  }

  private fun requireGatt(): BluetoothGatt = gatt ?: throw GattDisconnectedException(null)

  private fun findCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): BluetoothGattCharacteristic {
    ensureConnected()
    val service = requireGatt().getService(serviceUuid) ?: throw GattAttributeNotFoundException("Service $serviceUuid")
    return service.getCharacteristic(characteristicUuid)
      ?: throw GattAttributeNotFoundException("Characteristic $characteristicUuid in service $serviceUuid")
  }

  // ---------- connection lifecycle ----------

  private fun registerBluetoothStateReceiver() {
    val receiver = BluetoothStateChangeReceiver { _, _ -> onLinkLost(LinkLossReason.BLUETOOTH_TURNED_OFF) }
    context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    bluetoothStateReceiver = receiver
  }

  private fun releaseConnection() {
    gatt?.close()
    gatt = null
    bluetoothStateReceiver?.let { receiver ->
      try {
        context.unregisterReceiver(receiver)
      } catch (exception: IllegalArgumentException) {
        Log.w(logTag, "Bluetooth state receiver was not registered")
      }
    }
    bluetoothStateReceiver = null
  }

  private fun onLinkLost(reason: LinkLossReason) {
    when (connectionState.value) {
      GattConnectionState.Connected -> {
        Log.i(logTag, "GATT link lost: $reason")
        mutableConnectionState.value = GattConnectionState.Lost(reason)
        failPendingOperation(GattDisconnectedException(reason))
        // values already received are still delivered, then collectors get the exception
        notificationChannel.close(GattDisconnectedException(reason))
      }
      GattConnectionState.Connecting -> failPendingOperation(GattDisconnectedException(reason))
      else -> Unit
    }
  }

  /** Turns a callback's status into the pending operation's result: [successValue] or a failure. */
  private fun completeFromCallback(
    operation: GattOperation,
    characteristicUuid: UUID?,
    status: Int,
    successValue: () -> Any?,
  ) {
    val result = if (status == BluetoothGatt.GATT_SUCCESS) {
      Result.success(successValue())
    } else {
      val target = characteristicUuid?.let { " on $it" }.orEmpty()
      Result.failure(GattOperationFailedException(operation, "status $status$target"))
    }
    completePendingOperation(operation, characteristicUuid, result)
  }

  // ---------- Android callbacks (arrive on Android's Bluetooth threads) ----------
  // onCharacteristicRead and onCharacteristicChanged use the forms available at compileSdk 31;
  // Android 13 added overloads that pass the value directly.

  private val gattCallback = object : BluetoothGattCallback() {

    override fun onConnectionStateChange(callbackGatt: BluetoothGatt?, status: Int, newState: Int) {
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> completeFromCallback(GattOperation.CONNECT, null, status) { Unit }
        BluetoothProfile.STATE_DISCONNECTED -> {
          if (connectionState.value == GattConnectionState.Connecting) {
            val failure = GattOperationFailedException(GattOperation.CONNECT, "disconnected with status $status")
            completePendingOperation(GattOperation.CONNECT, null, Result.failure(failure))
          } else {
            onLinkLost(LinkLossReason.REMOTE_DISCONNECTED)
          }
        }
      }
    }

    override fun onServicesDiscovered(callbackGatt: BluetoothGatt?, status: Int) =
      completeFromCallback(GattOperation.DISCOVER_SERVICES, null, status) { callbackGatt?.services?.toList().orEmpty() }

    override fun onMtuChanged(callbackGatt: BluetoothGatt?, mtu: Int, status: Int) =
      completeFromCallback(GattOperation.REQUEST_MTU, null, status) { mtu }

    override fun onCharacteristicRead(callbackGatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
      val characteristicUuid = characteristic?.uuid ?: return
      completeFromCallback(GattOperation.READ_CHARACTERISTIC, characteristicUuid, status) {
        characteristic.value?.copyOf() ?: ByteArray(0)
      }
    }

    override fun onCharacteristicWrite(callbackGatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
      val characteristicUuid = characteristic?.uuid ?: return
      completeFromCallback(GattOperation.WRITE_CHARACTERISTIC, characteristicUuid, status) { Unit }
    }

    override fun onDescriptorWrite(callbackGatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
      if (descriptor?.uuid != CCCD_UUID) return
      val cccdOperation = pendingCccdOperation() ?: return
      completeFromCallback(cccdOperation, descriptor.characteristic?.uuid, status) { Unit }
    }

    override fun onCharacteristicChanged(callbackGatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
      val characteristicUuid = characteristic?.uuid ?: return
      val value = characteristic.value?.copyOf() ?: ByteArray(0)
      notificationChannel.trySend(GattNotification(characteristicUuid, value))
    }
  }
}
