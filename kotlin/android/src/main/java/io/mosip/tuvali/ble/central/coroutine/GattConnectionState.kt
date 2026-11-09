package io.mosip.tuvali.ble.central.coroutine

internal sealed class GattConnectionState {
  object Disconnected : GattConnectionState()
  object Connecting : GattConnectionState()
  object Connected : GattConnectionState()

  /** The link went down without us closing it. */
  data class Lost(val reason: LinkLossReason) : GattConnectionState()
}
