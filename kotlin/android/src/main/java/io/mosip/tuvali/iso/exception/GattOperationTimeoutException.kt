package io.mosip.tuvali.iso.exception

import io.mosip.tuvali.ble.central.coroutine.GattOperation
import kotlin.time.Duration

/** Android never answered the operation within its timeout. */
internal class GattOperationTimeoutException(val operation: GattOperation, timeout: Duration) :
  GattException("$operation got no answer within $timeout")
