package io.mosip.tuvali.ble.central.coroutine

/** Why the GATT link went down without us closing it. */
enum class LinkLossReason {
  REMOTE_DISCONNECTED,
  BLUETOOTH_TURNED_OFF,
}
