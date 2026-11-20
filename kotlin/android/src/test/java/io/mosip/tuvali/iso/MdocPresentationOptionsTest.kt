package io.mosip.tuvali.iso

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MdocPresentationOptionsTest {

  @Test
  fun `MdocPresentationOptions keeps the ISO defaults of 60 seconds to engage and 300 seconds of inactivity`() {
    val options = MdocPresentationOptions()

    assertEquals(60.seconds, options.engagementTimeout)
    assertEquals(300.seconds, options.inactivityTimeout)
  }

  @Test
  fun `MdocPresentationOptions accepts a timeout of exactly one year`() {
    val options = MdocPresentationOptions(inactivityTimeout = 365.days)

    assertEquals(365.days, options.inactivityTimeout)
  }

  @Test
  fun `MdocPresentationOptions throws IllegalArgumentException for a zero, negative, too long or infinite engagement timeout`() {
    listOf(Duration.ZERO, (-1).seconds, 366.days, Duration.INFINITE).forEach { timeout ->
      assertThrows(IllegalArgumentException::class.java) { MdocPresentationOptions(engagementTimeout = timeout) }
    }
  }

  @Test
  fun `MdocPresentationOptions throws IllegalArgumentException for a zero, negative, too long or infinite inactivity timeout`() {
    listOf(Duration.ZERO, (-1).seconds, 366.days, Duration.INFINITE).forEach { timeout ->
      assertThrows(IllegalArgumentException::class.java) { MdocPresentationOptions(inactivityTimeout = timeout) }
    }
  }
}
