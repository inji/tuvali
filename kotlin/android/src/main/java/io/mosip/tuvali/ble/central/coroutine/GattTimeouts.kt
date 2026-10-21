package io.mosip.tuvali.ble.central.coroutine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long to wait for Android's callback before giving up on a GATT operation. */
internal data class GattTimeouts(
  val connect: Duration = 10.seconds,
  val operation: Duration = 5.seconds,
)
