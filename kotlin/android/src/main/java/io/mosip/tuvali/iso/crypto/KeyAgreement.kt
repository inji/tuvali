package io.mosip.tuvali.iso.crypto

import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.util.BigIntegers

/**
 * ECKA-DH between our private key and the reader's public key (clause 9.1.1.5): ZAB, the
 * x coordinate of the shared point, always 32 bytes (left-padded when it starts with zeros).
 */
internal fun sharedSecret(deviceKey: EphemeralDeviceKey, readerKey: ReaderPublicKey): ByteArray {
  val agreement = ECDHBasicAgreement().apply { init(deviceKey.requirePrivateKey()) }
  return BigIntegers.asUnsignedByteArray(P256_FIELD_BYTES, agreement.calculateAgreement(readerKey.publicKey))
}
