package io.mosip.tuvali.iso.cbor

import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnsignedInteger
import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Decoding data received from a reader. Valid but non-canonical input is accepted; malformed or
 * hostile input fails with [CborDecodingException] (later mapped to session status 11).
 */
class CborDecodingTest {

  // ---------- valid input ----------

  @Test
  fun `decodeCbor reads integers written in a non-shortest form`() {
    val zeroWithOneByteArgument = "1800".hexToBytes()
    val zeroWithFourByteArgument = "1a00000000".hexToBytes()

    val first = decodeCbor(zeroWithOneByteArgument)
    val second = decodeCbor(zeroWithFourByteArgument)

    assertEquals(UnsignedInteger(0), first)
    assertEquals(UnsignedInteger(0), second)
  }

  @Test
  fun `decodeCbor reads a byte string whose length is written in a non-shortest form`() {
    val oneByteWithOneByteLength = "580101".hexToBytes()

    val decoded = decodeCbor(oneByteWithOneByteLength)

    assertEquals(ByteString("01".hexToBytes()), decoded)
  }

  @Test
  fun `decodeCbor reads true and null as simple values`() {
    val trueValue = "f5".hexToBytes()
    val nullValue = "f6".hexToBytes()

    val decodedTrue = decodeCbor(trueValue)
    val decodedNull = decodeCbor(nullValue)

    assertEquals(SimpleValue.TRUE, decodedTrue)
    assertEquals(SimpleValue.NULL, decodedNull)
  }

  @Test
  fun `decodeCbor returns an indefinite-length array that re-encodes with a definite length`() {
    // [_ 1, 2]
    val indefiniteArray = "9f0102ff".hexToBytes()

    val reEncoded = encodeCbor(decodeCbor(indefiniteArray))

    assertEquals("820102", reEncoded.toHex())
  }

  @Test
  fun `decodeCbor returns an indefinite-length byte string that re-encodes with a definite length`() {
    // (_ h'0102', h'030405')
    val indefiniteBytes = "5f42010243030405ff".hexToBytes()

    val reEncoded = encodeCbor(decodeCbor(indefiniteBytes))

    assertEquals("450102030405", reEncoded.toHex())
  }

  @Test
  fun `decodeCbor returns an indefinite-length text string that re-encodes with a definite length`() {
    // (_ "strea", "ming")
    val indefiniteText = "7f657374726561646d696e67ff".hexToBytes()

    val reEncoded = encodeCbor(decodeCbor(indefiniteText))

    assertEquals("6973747265616d696e67", reEncoded.toHex())
  }

  @Test
  fun `decodeCbor returns an indefinite-length map with a nested indefinite array that re-encodes definite`() {
    // {_ "a": 1, "b": [_ 2, 3]}
    val indefiniteMap = "bf61610161629f0203ffff".hexToBytes()

    val reEncoded = encodeCbor(decodeCbor(indefiniteMap))

    assertEquals("a26161016162820203", reEncoded.toHex())
  }

  @Test
  fun `decodeCbor followed by encodeCbor reproduces the device engagement test vector exactly`() {
    val deviceEngagement = SessionTestVectors.session.getString("deviceEngagement").hexToBytes()
    val reEncoded = encodeCbor(decodeCbor(deviceEngagement))

    assertEquals(deviceEngagement.toHex(), reEncoded.toHex())
  }

  @Test
  fun `decodeCbor accepts nesting one level below MAX_CBOR_NESTING_DEPTH`() {
    val nestedArrays = ByteArray(MAX_CBOR_NESTING_DEPTH - 1) { 0x81.toByte() } + byteArrayOf(0x00)

    val decoded = decodeCbor(nestedArrays)

    assertEquals(CborArray::class.java, decoded.javaClass)
  }

  // ---------- malformed or hostile input ----------

  @Test
  fun `decodeCbor throws CborDecodingException when the input is empty`() {
    val empty = ByteArray(0)

    assertThrows(CborDecodingException::class.java) { decodeCbor(empty) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when an integer argument is cut short`() {
    // a two-byte argument with only one byte present
    val truncatedInteger = "1903".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(truncatedInteger) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when an array has fewer items than its count`() {
    // an array of 2 with only 1 item
    val truncatedArray = "8201".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(truncatedArray) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when a byte string is shorter than its declared length`() {
    // declares 4 bytes, carries 2
    val truncatedBytes = "440102".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(truncatedBytes) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when a map key has no value`() {
    // a map of 1 with a key and no value
    val truncatedMap = "a101".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(truncatedMap) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when a tag has no content`() {
    // tag 24 with nothing after it
    val emptyTag = "d818".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(emptyTag) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when bytes follow the item`() {
    // the integer 0 followed by an extra byte
    val trailingBytes = "0001".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(trailingBytes) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException without allocating when a declared length exceeds the input`() {
    // declares a 4 GiB byte string, carries 1 byte
    val hugeDeclaredLength = "5affffffff00".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(hugeDeclaredLength) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when a map repeats a key`() {
    // {1: 1, 1: 2}
    val duplicateKey = "a201010102".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(duplicateKey) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when a nested map repeats a key`() {
    // {1: {1: 1, 1: 2}}
    val nestedDuplicateKey = "a101a201010102".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(nestedDuplicateKey) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when an indefinite byte string contains a non-string chunk`() {
    // (_ 1)
    val badChunk = "5f01ff".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(badChunk) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException when a break appears outside an indefinite item`() {
    val strayBreak = "ff".hexToBytes()

    assertThrows(CborDecodingException::class.java) { decodeCbor(strayBreak) }
  }

  @Test
  fun `decodeCbor throws CborDecodingException instead of overflowing the stack when nesting exceeds the limit`() {
    // 100,000 nested arrays
    val deeplyNested = ByteArray(100_000) { 0x81.toByte() } + byteArrayOf(0x00)

    assertThrows(CborDecodingException::class.java) { decodeCbor(deeplyNested) }
  }
}
