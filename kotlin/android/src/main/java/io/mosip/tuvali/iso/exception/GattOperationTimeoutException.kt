package io.mosip.tuvali.iso.exception

import io.mosip.tuvali.ble.central.coroutine.GattOperation
import kotlin.time.Duration

internal class GattOperationTimeoutException(val operation: GattOperation, timeout: Duration) :
  GattException("$operation got no answer within $timeout")
