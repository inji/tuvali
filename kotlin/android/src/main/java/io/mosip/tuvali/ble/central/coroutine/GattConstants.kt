package io.mosip.tuvali.ble.central.coroutine

import java.util.UUID

/** Client Characteristic Configuration Descriptor: writing it turns notifications or indications on. */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** ATT MTU every Bluetooth LE link starts with, and our fallback when negotiation fails. */
const val DEFAULT_ATT_MTU = 23

/** Largest ATT MTU Android supports; we always ask for it (ISO 18013-5 clause 8.3.3.1.1.5). */
const val MAX_ATT_MTU = 517

/**
 * Largest ATT MTU we use. Data goes out in chunks of MTU minus 3 bytes, and an attribute value
 * can hold at most 512 bytes, so anything above 515 would make a chunk too long to write.
 */
const val MAX_USABLE_ATT_MTU = 515
