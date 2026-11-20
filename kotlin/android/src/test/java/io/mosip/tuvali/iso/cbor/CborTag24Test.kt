package io.mosip.tuvali.iso.cbor

import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tag 24 (embedded CBOR) per RFC 7049 section 2.4. ISO 18013-5 clause 8.1 requires these
 * embedded bytes to be used exactly as sent or received in cryptographic operations.
 */
class CborTag24Test {

  @Test
  fun `tag24 wraps already-encoded bytes in tag 24 without re-encoding them`() {
    // RFC 8949 Appendix A example 24(h'6449455446')
    val embedded = "6449455446".hexToBytes()

    val encoded = encodeCbor(tag24(embedded))

    assertEquals("d818456449455446", encoded.toHex())
  }

  @Test
  fun `tag24 encodes a data item and wraps the encoded bytes in tag 24`() {
    val item = UnicodeString("IETF")

    val encoded = encodeCbor(tag24(item))

    assertEquals("d818456449455446", encoded.toHex())
  }

  @Test
  fun `tag24 of the device COSE key matches EDeviceKeyBytes from the test vectors`() {
    val deviceKey = SessionTestVectors.session.getJSONObject("deviceKey")
    val coseKey = decodeCbor(deviceKey.getString("coseKey").hexToBytes())

    val eDeviceKeyBytes = encodeCbor(tag24(coseKey))

    assertEquals(deviceKey.getString("eDeviceKeyBytes"), eDeviceKeyBytes.toHex())
  }

  @Test
  fun `untag24 returns the embedded bytes exactly as received`() {
    val deviceKey = SessionTestVectors.session.getJSONObject("deviceKey")
    val received = decodeCbor(deviceKey.getString("eDeviceKeyBytes").hexToBytes())

    val embedded = untag24(received)

    assertEquals(deviceKey.getString("coseKey"), embedded.toHex())
  }

  @Test
  fun `untag24 keeps a non-shortest embedded encoding unchanged`() {
    // 24(h'1800') embeds the integer 0 written in a non-shortest form
    val received = decodeCbor("d818421800".hexToBytes())

    val embedded = untag24(received)

    assertEquals("1800", embedded.toHex())
  }

  @Test
  fun `decodeTag24 decodes the item embedded in a tag 24 byte string`() {
    val received = decodeCbor("d818421800".hexToBytes())

    val item = decodeTag24(received)

    assertEquals(UnsignedInteger(0), item)
  }

  @Test
  fun `untag24 throws CborDecodingException when the byte string has no tag`() {
    val untagged = ByteString("00".hexToBytes())

    assertThrows(CborDecodingException::class.java) { untag24(untagged) }
  }

  @Test
  fun `untag24 throws CborDecodingException when the byte string has a tag other than 24`() {
    // 25(h'1800')
    val otherTag = decodeCbor("d819421800".hexToBytes())

    assertThrows(CborDecodingException::class.java) { untag24(otherTag) }
  }

  @Test
  fun `untag24 throws CborDecodingException when the item is not a byte string`() {
    val notAByteString = UnsignedInteger(24)

    assertThrows(CborDecodingException::class.java) { untag24(notAByteString) }
  }
}
