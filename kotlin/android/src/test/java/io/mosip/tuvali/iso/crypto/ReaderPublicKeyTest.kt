package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.cborMapOf
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.exception.InvalidReaderKeyException
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.asBytes
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import io.mosip.tuvali.testutils.valueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** EReaderKey checks before ECDH: ISO 18013-5 clauses 9.1.1.4 and 9.1.5.2, GOV.UK mDLR_MS_SE_06. */
class ReaderPublicKeyTest {

  private val readerCoseKey = SessionTestVectors.session.getJSONObject("readerKey").getString("coseKey").hexToBytes()
  private val readerX = decodeCbor(readerCoseKey).valueAt(-2).asBytes()
  private val readerY = decodeCbor(readerCoseKey).valueAt(-3).asBytes()

  @Test
  fun `fromCoseKey accepts the reader key of the test vectors`() {
    val key = ReaderPublicKey.fromCoseKey(readerCoseKey)

    assertEquals(readerX.toHex(), key.x.toHex())
    assertEquals(readerY.toHex(), key.y.toHex())
  }

  @Test
  fun `fromCoseKey ignores optional parameters it does not use`() {
    // label 2 is the optional COSE "kid" parameter
    val withKeyId = encodeCbor(cborMapOf(1L to 2L, -1L to 1L, -2L to readerX, -3L to readerY, 2L to byteArrayOf(7)))

    val key = ReaderPublicKey.fromCoseKey(withKeyId)

    assertEquals(readerX.toHex(), key.x.toHex())
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when the point is not on the curve`() {
    // the vector's x with its y changed in the last byte
    val offCurveY = readerY.copyOf().also { it[31] = (it[31] + 1).toByte() }

    assertThrows(InvalidReaderKeyException::class.java) {
      ReaderPublicKey.fromCoseKey(coseKey(y = offCurveY))
    }
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when both coordinates are zero`() {
    val zero = ByteArray(32)

    assertThrows(InvalidReaderKeyException::class.java) {
      ReaderPublicKey.fromCoseKey(coseKey(x = zero, y = zero))
    }
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when the curve is not P-256`() {
    // IANA COSE registry: crv 2 is P-384
    assertThrows(InvalidReaderKeyException::class.java) {
      ReaderPublicKey.fromCoseKey(coseKey(curve = 2L))
    }
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when the key type is not EC2`() {
    // IANA COSE registry: kty 1 is OKP
    assertThrows(InvalidReaderKeyException::class.java) {
      ReaderPublicKey.fromCoseKey(coseKey(keyType = 1L))
    }
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when the key is in compressed form`() {
    // compressed EC2 keys carry a sign bit (a boolean) in place of the y coordinate
    val compressed = encodeCbor(cborMapOf(1L to 2L, -1L to 1L, -2L to readerX, -3L to true))

    assertThrows(InvalidReaderKeyException::class.java) { ReaderPublicKey.fromCoseKey(compressed) }
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when a coordinate is not 32 bytes`() {
    val shortX = readerX.copyOfRange(1, 32)

    assertThrows(InvalidReaderKeyException::class.java) {
      ReaderPublicKey.fromCoseKey(coseKey(x = shortX))
    }
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when the key type is missing`() {
    val withoutKeyType = encodeCbor(cborMapOf(-1L to 1L, -2L to readerX, -3L to readerY))

    assertThrows(InvalidReaderKeyException::class.java) { ReaderPublicKey.fromCoseKey(withoutKeyType) }
  }

  @Test
  fun `fromCoseKey throws InvalidReaderKeyException when the key is not a map`() {
    val notAMap = encodeCbor(cborArrayOf(1L, 2L))

    assertThrows(InvalidReaderKeyException::class.java) { ReaderPublicKey.fromCoseKey(notAMap) }
  }

  private fun coseKey(
    keyType: Long = 2L,
    curve: Long = 1L,
    x: ByteArray = readerX,
    y: ByteArray = readerY,
  ): ByteArray = encodeCbor(cborMapOf(1L to keyType, -1L to curve, -2L to x, -3L to y))
}
