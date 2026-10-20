package io.mosip.tuvali.iso.engagement

import co.nstant.`in`.cbor.model.DataItem
import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.cborMapOf
import java.nio.ByteBuffer
import java.util.UUID

// DeviceRetrievalMethod for BLE (Table 7) and its BleOptions keys (clause 8.2.2.3)
private const val RETRIEVAL_TYPE_BLE = 2L
private const val RETRIEVAL_VERSION_BLE = 1L
private const val BLE_OPTION_PERIPHERAL_SERVER_MODE = 0L
private const val BLE_OPTION_CENTRAL_CLIENT_MODE = 1L
private const val BLE_OPTION_CENTRAL_CLIENT_UUID = 11L

/**
 * One way the mdoc offers to transfer data, listed in the engagement's DeviceRetrievalMethods
 * (clause 8.2.1.1). Only BLE central client mode exists today; BLE peripheral server mode,
 * Wi-Fi Aware or NFC would each be a further case with its own options.
 */
internal sealed class RetrievalMethod {
  /** The DeviceRetrievalMethod array: [type, version, options]. */
  abstract fun toCbor(): DataItem

  /**
   * BLE with the mdoc as central client only: the reader advertises [uuid] and the mdoc connects.
   * The server-mode UUID (10) and device address (20) are left out, as clause 8.3.3.1.1.2 requires
   * when server mode is not supported.
   */
  class BleCentralClient(val uuid: UUID) : RetrievalMethod() {
    override fun toCbor(): DataItem = cborArrayOf(
      RETRIEVAL_TYPE_BLE,
      RETRIEVAL_VERSION_BLE,
      cborMapOf(
        BLE_OPTION_PERIPHERAL_SERVER_MODE to false,
        BLE_OPTION_CENTRAL_CLIENT_MODE to true,
        BLE_OPTION_CENTRAL_CLIENT_UUID to uuid.toBytes(),
      ),
    )
  }
}

/** 16 bytes in RFC 4122 byte order (most significant first), as clause 8.3.3.1.1.2 requires. */
private fun UUID.toBytes(): ByteArray =
  ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
