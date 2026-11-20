package io.mosip.tuvali.iso.exception

import io.mosip.tuvali.ble.central.coroutine.GattOperation

internal class GattOperationFailedException(val operation: GattOperation, detail: String) :
  GattException("$operation failed: $detail")
