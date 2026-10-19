package io.mosip.tuvali.iso.engagement

import io.mosip.tuvali.testutils.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Test

/** RFC 4648 base64url without padding, as the QR code needs (ISO 18013-5 clause 8.2.2.3). */
class Base64UrlTest {

  @Test
  fun `encodeBase64UrlWithoutPadding matches the RFC 4648 test vectors with padding removed`() {
    // RFC 4648 section 10: "", "f", "fo", "foo", "foob", "fooba", "foobar"
    val inputs = listOf("", "f", "fo", "foo", "foob", "fooba", "foobar")

    val encoded = inputs.map { encodeBase64UrlWithoutPadding(it.toByteArray()) }

    assertEquals(listOf("", "Zg", "Zm8", "Zm9v", "Zm9vYg", "Zm9vYmE", "Zm9vYmFy"), encoded)
  }

  @Test
  fun `encodeBase64UrlWithoutPadding uses minus and underscore instead of plus and slash`() {
    // 0xfb 0xff encodes to "+/8=" in standard base64
    val bytes = "fbff".hexToBytes()

    val encoded = encodeBase64UrlWithoutPadding(bytes)

    assertEquals("-_8", encoded)
  }
}
