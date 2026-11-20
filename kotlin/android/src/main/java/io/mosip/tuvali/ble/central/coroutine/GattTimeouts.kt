package io.mosip.tuvali.ble.central.coroutine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class GattTimeouts(
  val connect: Duration = 10.seconds,
  val operation: Duration = 5.seconds,
)
