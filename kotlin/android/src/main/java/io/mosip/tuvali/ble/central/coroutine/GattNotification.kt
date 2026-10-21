package io.mosip.tuvali.ble.central.coroutine

import java.util.UUID

/** A value the remote GATT server pushed to us through a notification or indication. */
internal class GattNotification(val characteristicUuid: UUID, val value: ByteArray)
