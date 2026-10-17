package io.mosip.tuvali

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TestHarnessTest {

  @Test
  fun `runTest skips virtual time for delays`() = runTest {
    delay(60_000)
    assertEquals(60_000L, currentTime)
  }

  @Test
  fun `mockk can stub an interface`() {
    val source = mockk<() -> String>()
    every { source() } returns "stubbed"
    assertEquals("stubbed", source())
  }
}
