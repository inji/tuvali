package io.mosip.tuvali.ble.exception

import io.mosip.tuvali.ble.central.coroutine.LinkLossReason

/** The link is down (lost, or closed by us), so the operation could not finish. */
class GattDisconnectedException(val reason: LinkLossReason?) :
  GattException(if (reason == null) "GATT client is not connected" else "GATT link lost: $reason")
