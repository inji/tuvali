package io.mosip.tuvali

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Confirms the unit test toolchain (coroutines-test virtual time, mockk) works in this module. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestHarnessTest {

  @Test
  fun `runTest advances virtual time through a delay without really waiting`() = runTest {
    val oneMinuteInMillis = 60_000L

    delay(oneMinuteInMillis)

    assertEquals(oneMinuteInMillis, currentTime)
  }

  @Test
  fun `mockk returns the stubbed value from a mocked function`() {
    val source = mockk<() -> String>()
    every { source() } returns "stubbed"

    val result = source()

    assertEquals("stubbed", result)
  }
}
