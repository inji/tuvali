package io.mosip.tuvali.iso.cbor

import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.testutils.hexToBytes
import org.junit.Assert.assertThrows
import org.junit.Test

/** Strict encoding checks for what Tuvali sends: ISO 18013-5 clause 8.1 rules 1 to 3. */
class CborStrictTest {

  @Test
  fun `requireStrictCbor accepts shortest integers, lengths and definite containers`() {
    // {"a": [1, 24, 256], "b": h'00'} with every argument in its shortest form
    val strict = "a2616183011818190100616241 00".replace(" ", "").hexToBytes()

    requireStrictCbor(strict)
  }

  @Test
  fun `requireStrictCbor throws CborDecodingException for an integer in a longer form than needed`() {
    // 1 written with a one-byte argument (18 01) instead of inline (01)
    val notShortest = "1801".hexToBytes()

    assertThrows(CborDecodingException::class.java) { requireStrictCbor(notShortest) }
  }

  @Test
  fun `requireStrictCbor throws CborDecodingException for a length in a longer form than needed`() {
    // a 1-byte byte string with its length written as 58 01 instead of inline 41
    val notShortest = "580100".hexToBytes()

    assertThrows(CborDecodingException::class.java) { requireStrictCbor(notShortest) }
  }

  @Test
  fun `requireStrictCbor throws CborDecodingException for an indefinite-length array`() {
    // [_ 1, 2]
    val indefinite = "9f0102ff".hexToBytes()

    assertThrows(CborDecodingException::class.java) { requireStrictCbor(indefinite) }
  }

  @Test
  fun `requireStrictCbor throws CborDecodingException for a non-shortest argument nested inside a map`() {
    // {"a": 1} with the value 1 written as 18 01
    val nested = "a161611801".hexToBytes()

    assertThrows(CborDecodingException::class.java) { requireStrictCbor(nested) }
  }
}
