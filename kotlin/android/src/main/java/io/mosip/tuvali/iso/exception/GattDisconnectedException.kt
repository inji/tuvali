package io.mosip.tuvali.iso.exception

import io.mosip.tuvali.ble.central.coroutine.LinkLossReason

internal class GattDisconnectedException(val reason: LinkLossReason?) :
  GattException(if (reason == null) "GATT client is not connected" else "GATT link lost: $reason")
