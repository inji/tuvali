package io.mosip.tuvali.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CRC-16/KERMIT used on every chunk of today's Tuvali transfer. */
class CheckValueTest {

  // The CRC catalogue defines each CRC's check value as its CRC of the ASCII string "123456789"
  private val catalogueCheckInput = "123456789".toByteArray(Charsets.US_ASCII)
  private val kermitCheckValue: UShort = 0x2189u

  @Test
  fun `get returns the CRC-16 Kermit catalogue check value for the ASCII digits 1 to 9`() {
    val data = catalogueCheckInput

    val crc = CheckValue.get(data)

    assertEquals(kermitCheckValue, crc)
  }

  @Test
  fun `verify returns true when the CRC matches the data`() {
    val data = catalogueCheckInput

    val matches = CheckValue.verify(data, kermitCheckValue)

    assertTrue(matches)
  }

  @Test
  fun `verify returns false when the CRC does not match the data`() {
    val data = catalogueCheckInput
    val wrongCrc = (kermitCheckValue + 1u).toUShort()

    val matches = CheckValue.verify(data, wrongCrc)

    assertFalse(matches)
  }
}
