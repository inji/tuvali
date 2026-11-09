package io.mosip.tuvali.ble.central.coroutine

import java.util.UUID

internal class GattNotification(val characteristicUuid: UUID, val value: ByteArray)
