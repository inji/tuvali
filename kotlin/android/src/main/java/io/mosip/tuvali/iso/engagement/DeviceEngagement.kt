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

private const val ENGAGEMENT_VERSION = "1.0"
private const val CIPHER_SUITE_1 = 1L

// DeviceEngagement map keys (clause 8.2.1.1)
private const val KEY_VERSION = 0L
private const val KEY_SECURITY = 1L
private const val KEY_DEVICE_RETRIEVAL_METHODS = 2L

/**
 * The device engagement structure that starts an ISO 18013-5 session (clause 8.2.1.1), whatever
 * carries it to the reader: a QR code today (see [toQrText]), NFC handover later.
 *
 * It lists the [retrievalMethods] the wallet offers; today that is BLE with the mdoc as central
 * client only, where the reader advertises the method's UUID and the wallet connects to it. With
 * no methods, DeviceRetrievalMethods is left out, as NFC engagement requires. [bytes] are kept
 * exactly as encoded, because the session transcript (clause 9.1.5.1) must use the same bytes the
 * reader received.
 */
internal class DeviceEngagement private constructor(
  val deviceKey: EphemeralDeviceKey,
  internal val retrievalMethods: List<RetrievalMethod>,
) {
  // encoded once and wrapped as-is, so the engagement and eDeviceKeyBytes hold the very same key bytes
  private val encodedCoseKey: ByteArray = encodeCbor(deviceKey.toCoseKey())
  private val encodedDeviceKey: ByteArray = encodeCbor(tag24(encodedCoseKey))

  private val encoded: ByteArray = encodeCbor(cborMapOf(*engagementEntries().toTypedArray()))

  /** The DeviceEngagement CBOR exactly as sent to the reader. */
  val bytes: ByteArray get() = encoded.copyOf()

  /** EDeviceKeyBytes: #6.24(bstr .cbor EDeviceKey), as used for the BLE Ident (clause 8.3.3.1.1.3). */
  val eDeviceKeyBytes: ByteArray get() = encodedDeviceKey.copyOf()

  private fun engagementEntries(): List<Pair<Any, Any?>> = buildList {
    add(KEY_VERSION to ENGAGEMENT_VERSION)
    add(KEY_SECURITY to cborArrayOf(CIPHER_SUITE_1, tag24(encodedCoseKey)))
    if (retrievalMethods.isNotEmpty()) {
      add(KEY_DEVICE_RETRIEVAL_METHODS to cborArrayOf(*retrievalMethods.map { it.toCbor() }.toTypedArray()))
    }
  }

  companion object {
    /** A new engagement with a fresh ephemeral key, offering BLE central client mode with a fresh UUID. */
    fun create(random: SecureRandom = SecureRandom()): DeviceEngagement {
      val bleCentralClient = RetrievalMethod.BleCentralClient(randomUuid(random))
      return DeviceEngagement(EphemeralDeviceKey.generate(random), listOf(bleCentralClient))
    }

    /** An engagement for a given key and retrieval methods; used with fixed test vectors. */
    internal fun create(deviceKey: EphemeralDeviceKey, retrievalMethods: List<RetrievalMethod>): DeviceEngagement =
      DeviceEngagement(deviceKey, retrievalMethods)
  }
}

/** A random (version 4) UUID of the RFC 4122 variant, drawn from [random]. */
private fun randomUuid(random: SecureRandom): UUID {
  val bytes = ByteArray(16).also(random::nextBytes)
  bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte() // version 4
  bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // variant '10x'
  val buffer = ByteBuffer.wrap(bytes)
  return UUID(buffer.long, buffer.long)
}
