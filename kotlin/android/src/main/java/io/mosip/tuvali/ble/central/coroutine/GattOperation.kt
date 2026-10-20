package io.mosip.tuvali.ble.central.coroutine

/** The GATT operations the client runs, one at a time. */
enum class GattOperation {
  CONNECT,
  DISCOVER_SERVICES,
  REQUEST_MTU,
  READ_CHARACTERISTIC,
  WRITE_CHARACTERISTIC,
  ENABLE_NOTIFICATIONS,
  DISABLE_NOTIFICATIONS,
}
