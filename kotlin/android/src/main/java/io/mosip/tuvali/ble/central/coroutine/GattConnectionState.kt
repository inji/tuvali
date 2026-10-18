package io.mosip.tuvali.ble.central.coroutine

/** Where the client's connection stands; [Lost] says why the link went down. */
sealed class GattConnectionState {
  object Disconnected : GattConnectionState()
  object Connecting : GattConnectionState()
  object Connected : GattConnectionState()

  /** The link went down without us closing it. */
  data class Lost(val reason: LinkLossReason) : GattConnectionState()
}
