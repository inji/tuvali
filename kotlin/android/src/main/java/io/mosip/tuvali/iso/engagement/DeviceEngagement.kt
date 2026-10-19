package io.mosip.tuvali.iso.engagement

import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.cborMapOf
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.tag24
import io.mosip.tuvali.iso.crypto.EphemeralDeviceKey
import io.mosip.tuvali.iso.crypto.toCoseKey
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

private const val QR_SCHEME = "mdoc:"
private const val ENGAGEMENT_VERSION = "1.0"
private const val CIPHER_SUITE_1 = 1L

// DeviceEngagement map keys (clause 8.2.1.1)
private const val KEY_VERSION = 0L
private const val KEY_SECURITY = 1L
private const val KEY_DEVICE_RETRIEVAL_METHODS = 2L

// DeviceRetrievalMethod for BLE (Table 7) and its BleOptions keys (clause 8.2.2.3)
private const val RETRIEVAL_TYPE_BLE = 2L
private const val RETRIEVAL_VERSION_BLE = 1L
private const val BLE_OPTION_PERIPHERAL_SERVER_MODE = 0L
private const val BLE_OPTION_CENTRAL_CLIENT_MODE = 1L
private const val BLE_OPTION_CENTRAL_CLIENT_UUID = 11L

/**
 * What the wallet shows as a QR code to start an ISO 18013-5 session (clauses 8.2.1.1, 8.2.2.3).
 *
 * It offers BLE with the mdoc as central client only: the reader advertises [bleClientUuid] and
 * the wallet connects to it. [bytes] are kept exactly as encoded, because the session transcript
 * (clause 9.1.5.1) must use the same bytes the reader decoded from the QR code.
 */
class DeviceEngagement private constructor(
  val deviceKey: EphemeralDeviceKey,
  val bleClientUuid: UUID,
) {
  // encoded once and wrapped as-is, so the engagement and eDeviceKeyBytes hold the very same key bytes
  private val encodedCoseKey: ByteArray = encodeCbor(deviceKey.toCoseKey())
  private val encodedDeviceKey: ByteArray = encodeCbor(tag24(encodedCoseKey))

  private val encoded: ByteArray = encodeCbor(
    cborMapOf(
      KEY_VERSION to ENGAGEMENT_VERSION,
      KEY_SECURITY to cborArrayOf(CIPHER_SUITE_1, tag24(encodedCoseKey)),
      KEY_DEVICE_RETRIEVAL_METHODS to cborArrayOf(
        cborArrayOf(
          RETRIEVAL_TYPE_BLE,
          RETRIEVAL_VERSION_BLE,
          cborMapOf(
            BLE_OPTION_PERIPHERAL_SERVER_MODE to false,
            BLE_OPTION_CENTRAL_CLIENT_MODE to true,
            BLE_OPTION_CENTRAL_CLIENT_UUID to bleClientUuid.toBytes(),
          ),
        ),
      ),
    )
  )

  /** The DeviceEngagement CBOR exactly as put in the QR code. */
  val bytes: ByteArray get() = encoded.copyOf()

  /** EDeviceKeyBytes: #6.24(bstr .cbor EDeviceKey), as used for the BLE Ident (clause 8.3.3.1.1.3). */
  val eDeviceKeyBytes: ByteArray get() = encodedDeviceKey.copyOf()

  /** "mdoc:" followed by the engagement bytes in base64url without padding. */
  val qrText: String = QR_SCHEME + encodeBase64UrlWithoutPadding(encoded)

  companion object {
    /** A new engagement with a fresh ephemeral key and a fresh BLE UUID. */
    fun create(random: SecureRandom = SecureRandom()): DeviceEngagement =
      DeviceEngagement(EphemeralDeviceKey.generate(random), randomUuid(random))

    /** An engagement for a given key and UUID; used with fixed test vectors. */
    internal fun create(deviceKey: EphemeralDeviceKey, bleClientUuid: UUID): DeviceEngagement =
      DeviceEngagement(deviceKey, bleClientUuid)
  }
}

/** 16 bytes in RFC 4122 byte order (most significant first), as clause 8.3.3.1.1.2 requires. */
private fun UUID.toBytes(): ByteArray =
  ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

/** A random (version 4) UUID of the RFC 4122 variant, drawn from [random]. */
private fun randomUuid(random: SecureRandom): UUID {
  val bytes = ByteArray(16).also(random::nextBytes)
  bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte() // version 4
  bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // variant '10x'
  val buffer = ByteBuffer.wrap(bytes)
  return UUID(buffer.long, buffer.long)
}
