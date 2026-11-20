package io.mosip.tuvali.iso.cbor

import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.UnicodeString
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Encoding rules from ISO 18013-5 clause 8.1: shortest integers, shortest lengths, definite
 * lengths, no duplicate keys, key order as built. Expected bytes come from RFC 8949 Appendix A
 * and from the independent session test vectors.
 */
class CborEncodingTest {

  @Test
  fun `encodeCbor writes unsigned integers up to 23 inside the initial byte`() {
    val values = cborArrayOf(0, 23)

    val encoded = encodeCbor(values)

    assertEquals("820017", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes unsigned integers from 24 to 255 with a one-byte argument`() {
    val values = cborArrayOf(24, 255)

    val encoded = encodeCbor(values)

    assertEquals("82181818ff", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes unsigned integers from 256 to 65535 with a two-byte argument`() {
    val values = cborArrayOf(256, 1000)

    val encoded = encodeCbor(values)

    assertEquals("821901001903e8", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes unsigned integers that fit in 32 bits with a four-byte argument`() {
    val value = cborArrayOf(1000000)

    val encoded = encodeCbor(value)

    assertEquals("811a000f4240", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes unsigned integers above 32 bits with an eight-byte argument`() {
    val value = cborArrayOf(1000000000000L)

    val encoded = encodeCbor(value)

    assertEquals("811b000000e8d4a51000", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes negative integers in the shortest form`() {
    val values = cborArrayOf(-1, -24, -25, -1000)

    val encoded = encodeCbor(values)

    // -1 -> 20, -24 -> 37, -25 -> 38 18, -1000 -> 39 03 e7
    assertEquals("84203738183903e7", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes a byte string length up to 23 inside the initial byte`() {
    val bytes = ByteString(ByteArray(23))

    val encoded = encodeCbor(bytes)

    assertEquals("57", encoded.toHex().take(2))
    assertEquals(1 + 23, encoded.size)
  }

  @Test
  fun `encodeCbor writes a byte string length of 24 with a one-byte length`() {
    val bytes = ByteString(ByteArray(24))

    val encoded = encodeCbor(bytes)

    assertEquals("5818", encoded.toHex().take(4))
    assertEquals(2 + 24, encoded.size)
  }

  @Test
  fun `encodeCbor writes a byte string length of 256 with a two-byte length`() {
    val bytes = ByteString(ByteArray(256))

    val encoded = encodeCbor(bytes)

    assertEquals("590100", encoded.toHex().take(6))
    assertEquals(3 + 256, encoded.size)
  }

  @Test
  fun `encodeCbor writes an empty byte string as a single byte`() {
    val bytes = ByteString(ByteArray(0))

    val encoded = encodeCbor(bytes)

    assertEquals("40", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes text strings as UTF-8 bytes with their byte length`() {
    val ascii = UnicodeString("IETF")
    val nonAscii = UnicodeString("ü")

    val encodedAscii = encodeCbor(ascii)
    val encodedNonAscii = encodeCbor(nonAscii)

    assertEquals("6449455446", encodedAscii.toHex())
    assertEquals("62c3bc", encodedNonAscii.toHex())
  }

  @Test
  fun `encodeCbor writes an array count of 24 with a one-byte count`() {
    val array = cborArrayOf(*Array(24) { 0 })

    val encoded = encodeCbor(array)

    assertEquals("9818", encoded.toHex().take(4))
  }

  @Test
  fun `encodeCbor writes nested arrays with definite lengths`() {
    val nested = cborArrayOf(1, cborArrayOf(2, 3), cborArrayOf(4, 5))

    val encoded = encodeCbor(nested)

    assertEquals("8301820203820405", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes false, true and null as simple values`() {
    val values = cborArrayOf(false, true, null)

    val encoded = encodeCbor(values)

    assertEquals("83f4f5f6", encoded.toHex())
  }

  @Test
  fun `encodeCbor writes a map with text keys and an array value`() {
    val map = cborMapOf("a" to 1, "b" to cborArrayOf(2, 3))

    val encoded = encodeCbor(map)

    assertEquals("a26161016162820203", encoded.toHex())
  }

  @Test
  fun `encodeCbor keeps map keys in the order they were added instead of sorting them`() {
    val map = cborMapOf(3 to 0, 1 to 0)

    val encoded = encodeCbor(map)

    assertEquals("a203000100", encoded.toHex())
  }

  @Test
  fun `cborMapOf throws IllegalArgumentException when a key is repeated`() {
    val repeatedKey = 1

    assertThrows(IllegalArgumentException::class.java) {
      cborMapOf(repeatedKey to "first", repeatedKey to "second")
    }
  }

  @Test
  fun `encodeCbor reproduces the device COSE key bytes from the independent test vectors`() {
    val vectorCoseKey = SessionTestVectors.session.getJSONObject("deviceKey").getString("coseKey").hexToBytes()
    val decodedVector = decodeCbor(vectorCoseKey) as co.nstant.`in`.cbor.model.Map
    val x = (decodedVector.get(NegativeInteger(-2)) as ByteString).bytes
    val y = (decodedVector.get(NegativeInteger(-3)) as ByteString).bytes
    val coseKey = cborMapOf(1 to 2, -1 to 1, -2 to x, -3 to y)

    val encoded = encodeCbor(coseKey)

    assertEquals(vectorCoseKey.toHex(), encoded.toHex())
  }
}
