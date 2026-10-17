package io.mosip.tuvali.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckValueTest {

  // CRC-16/KERMIT catalogue check value for the ASCII string "123456789"
  private val catalogueInput = "123456789".toByteArray(Charsets.US_ASCII)
  private val catalogueCheck: UShort = 0x2189u

  @Test
  fun `computes the CRC-16 Kermit catalogue check value`() {
    assertEquals(catalogueCheck, CheckValue.get(catalogueInput))
  }

  @Test
  fun `verify accepts the matching CRC and rejects a different one`() {
    assertTrue(CheckValue.verify(catalogueInput, catalogueCheck))
    assertFalse(CheckValue.verify(catalogueInput, (catalogueCheck + 1u).toUShort()))
  }
}
