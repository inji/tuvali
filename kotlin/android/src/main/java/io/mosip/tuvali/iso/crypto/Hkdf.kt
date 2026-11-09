package io.mosip.tuvali.iso.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/** HKDF-SHA256 (RFC 5869). A null [salt] means "no salt", which HKDF treats as 32 zero bytes. */
internal fun hkdfSha256(inputKeyMaterial: ByteArray, salt: ByteArray?, info: String, length: Int): ByteArray {
  val generator = HKDFBytesGenerator(SHA256Digest()).apply {
    init(HKDFParameters(inputKeyMaterial, salt, info.toByteArray(Charsets.UTF_8)))
  }
  return ByteArray(length).also { output -> generator.generateBytes(output, 0, length) }
}
