package io.mosip.tuvali.testutils.ble

import io.mosip.tuvali.ble.central.coroutine.SuspendingGattClient
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Starts [operation] and runs it until it is waiting for Android's callback. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.startOperation(operation: suspend () -> T): Deferred<T> =
  async { operation() }.also { runCurrent() }

/** Connects [client] to [bluetooth], playing Android's successful connection callback. */
suspend fun TestScope.connectSuccessfully(client: SuspendingGattClient, bluetooth: MockAndroidBluetooth) {
  val connecting = startOperation { client.connect(bluetooth.device) }
  bluetooth.reportConnected()
  connecting.await()
}
