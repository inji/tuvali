package io.mosip.tuvali.iso.exception

import io.mosip.tuvali.ble.central.coroutine.GattOperation

/** Android refused to start the operation, or reported a non-success status for it. */
internal class GattOperationFailedException(val operation: GattOperation, detail: String) :
  GattException("$operation failed: $detail")
