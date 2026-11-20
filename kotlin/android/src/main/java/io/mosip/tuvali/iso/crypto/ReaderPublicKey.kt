package io.mosip.tuvali.iso.crypto

import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.UnsignedInteger
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.exception.InvalidReaderKeyException
import java.math.BigInteger
import org.bouncycastle.crypto.params.ECPublicKeyParameters

/**
 * The reader's ephemeral public key (EReaderKey), checked before it is used for ECDH.
 *
 * Only an uncompressed EC2 key on P-256 whose point lies on the curve is accepted (clauses
 * 9.1.1.4, 9.1.5.2); anything else could leak information about our private key in ECDH.
 * Optional COSE parameters other than crv, x and y are ignored, as clause 9.1.5.2 asks.
 */
internal class ReaderPublicKey private constructor(val publicKey: ECPublicKeyParameters) {

  private val xBytes: ByteArray = publicKey.q.affineXCoord.encoded
  private val yBytes: ByteArray = publicKey.q.affineYCoord.encoded

  val x: ByteArray get() = xBytes.copyOf()
  val y: ByteArray get() = yBytes.copyOf()

  companion object {
    fun fromCoseKey(encodedCoseKey: ByteArray): ReaderPublicKey {
      val coseKey = decodeCbor(encodedCoseKey) as? CborMap
        ?: throw InvalidReaderKeyException("EReaderKey is not a COSE_Key map")

      requireLabel(coseKey, COSE_LABEL_KEY_TYPE, COSE_KEY_TYPE_EC2, "key type must be EC2")
      requireLabel(coseKey, COSE_LABEL_CURVE, COSE_CURVE_P256, "curve must be P-256")
      val x = coordinate(coseKey, COSE_LABEL_X, "x")
      val y = coordinate(coseKey, COSE_LABEL_Y, "y (the key must be uncompressed)")

      val point = try {
        P256.curve.validatePoint(BigInteger(1, x), BigInteger(1, y))
      } catch (exception: IllegalArgumentException) {
        throw InvalidReaderKeyException("EReaderKey is not a point on P-256")
      }
      return ReaderPublicKey(ECPublicKeyParameters(point, P256))
    }

    private fun requireLabel(coseKey: CborMap, label: Long, expected: Long, problem: String) {
      val value = coseKey.get(labelItem(label)) as? UnsignedInteger
      if (value?.value != BigInteger.valueOf(expected)) throw InvalidReaderKeyException("EReaderKey $problem")
    }

    private fun coordinate(coseKey: CborMap, label: Long, name: String): ByteArray {
      val bytes = (coseKey.get(labelItem(label)) as? ByteString)?.bytes
      if (bytes == null || bytes.size != P256_FIELD_BYTES) {
        throw InvalidReaderKeyException("EReaderKey $name must be a $P256_FIELD_BYTES-byte string")
      }
      return bytes
    }

    private fun labelItem(label: Long): DataItem = if (label >= 0) UnsignedInteger(label) else NegativeInteger(label)
  }
}
