package io.mosip.tuvali.ble.central.coroutine

import android.bluetooth.BluetoothGatt
import io.mockk.verify
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.iso.exception.GattOperationFailedException
import io.mosip.tuvali.iso.exception.GattOperationTimeoutException
import io.mosip.tuvali.testutils.ble.MockAndroidBluetooth
import io.mosip.tuvali.testutils.ble.connectSuccessfully
import io.mosip.tuvali.testutils.ble.startOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingGattClientConnectionTest {

  private val bluetooth = MockAndroidBluetooth()
  private val timeouts = GattTimeouts(connect = 10.seconds, operation = 5.seconds)
  private val client = SuspendingGattClient(bluetooth.context, timeouts)

  @Test
  fun `connect completes and reports Connected when Android reports a successful connection`() = runTest {
    val connecting = startOperation { client.connect(bluetooth.device) }

    bluetooth.reportConnected()
    connecting.await()

    assertEquals(GattConnectionState.Connected, client.connectionState.value)
  }

  @Test
  fun `connect asks Android for a high-priority connection once connected`() = runTest {
    connectSuccessfully(client, bluetooth)

    verify { bluetooth.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
  }

  @Test
  fun `connect registers for Bluetooth on and off changes`() = runTest {
    connectSuccessfully(client, bluetooth)

    assertTrue(bluetooth.hasRegisteredReceiver)
  }

  @Test
  fun `connect throws GattOperationFailedException when Android cannot start the connection`() = runTest {
    bluetooth.gattReturnedByConnect = null

    val failure = runCatching { client.connect(bluetooth.device) }.exceptionOrNull()

    assertTrue(failure is GattOperationFailedException)
    assertEquals(GattConnectionState.Disconnected, client.connectionState.value)
  }

  @Test
  fun `connect throws GattOperationFailedException when Android reports a failed connection`() = runTest {
    // 133 is Android's generic GATT_ERROR, commonly seen when a connection attempt fails
    val gattError = 133
    val connecting = startOperation { runCatching { client.connect(bluetooth.device) } }

    bluetooth.reportDisconnected(status = gattError)

    val failure = connecting.await().exceptionOrNull()
    assertTrue(failure is GattOperationFailedException)
    assertEquals(GattConnectionState.Disconnected, client.connectionState.value)
  }

  @Test
  fun `connect throws GattOperationTimeoutException when Android never reports the connection`() = runTest {
    val connecting = startOperation { runCatching { client.connect(bluetooth.device) } }

    advanceTimeBy(timeouts.connect + 1.milliseconds)

    assertTrue(connecting.await().exceptionOrNull() is GattOperationTimeoutException)
  }

  @Test
  fun `connectionState becomes Lost with RemoteDisconnected when the link drops while idle`() = runTest {
    connectSuccessfully(client, bluetooth)

    bluetooth.reportDisconnected()

    assertEquals(GattConnectionState.Lost(LinkLossReason.REMOTE_DISCONNECTED), client.connectionState.value)
  }

  @Test
  fun `connectionState becomes Lost with BluetoothTurnedOff when Bluetooth is switched off while idle`() = runTest {
    connectSuccessfully(client, bluetooth)

    bluetooth.turnBluetoothOff()

    assertEquals(GattConnectionState.Lost(LinkLossReason.BLUETOOTH_TURNED_OFF), client.connectionState.value)
  }

  @Test
  fun `close disconnects, closes the GATT client and unregisters the Bluetooth receiver`() = runTest {
    connectSuccessfully(client, bluetooth)

    client.close()

    verify { bluetooth.gatt.disconnect() }
    verify { bluetooth.gatt.close() }
    assertFalse(bluetooth.hasRegisteredReceiver)
    assertEquals(GattConnectionState.Disconnected, client.connectionState.value)
  }

  @Test
  fun `operations throw GattDisconnectedException after close`() = runTest {
    connectSuccessfully(client, bluetooth)
    client.close()

    val failure = runCatching { client.discoverServices() }.exceptionOrNull()

    assertTrue(failure is GattDisconnectedException)
  }

  @Test
  fun `connect throws IllegalStateException when the client is already connected`() = runTest {
    connectSuccessfully(client, bluetooth)

    val failure = runCatching { client.connect(bluetooth.device) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
  }

  @Test
  fun `connectionState starts as Disconnected before connect is called`() {
    val freshClient = SuspendingGattClient(bluetooth.context, timeouts)

    val state = freshClient.connectionState.value

    assertEquals(GattConnectionState.Disconnected, state)
  }
}
