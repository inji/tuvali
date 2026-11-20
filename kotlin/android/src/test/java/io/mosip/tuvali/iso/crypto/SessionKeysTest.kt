package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/** SKReader and SKDevice from HKDF-SHA256: ISO 18013-5 clause 9.1.1.5. */
class SessionKeysTest {

  private val vectors = SessionTestVectors.session
  private val sharedSecret = vectors.getString("sharedSecret").hexToBytes()

  @Test
  fun `deriveSessionKeys reproduces SKReader and SKDevice of the test vectors`() {
    val transcript = vectors.getString("sessionTranscriptBytes").hexToBytes()

    val keys = deriveSessionKeys(sharedSecret, transcript)

    assertEquals(vectors.getString("skReader"), keys.skReader.toHex())
    assertEquals(vectors.getString("skDevice"), keys.skDevice.toHex())
  }

  @Test
  fun `deriveSessionKeys gives different keys when the transcript has a non-canonical reader key`() {
    val oddVector = vectors.getJSONObject("nonCanonicalReaderKey")
    // same key material, but the transcript embeds the reader key bytes exactly as received
    val oddTranscript = oddVector.getString("sessionTranscriptBytes").hexToBytes()

    val keys = deriveSessionKeys(sharedSecret, oddTranscript)

    assertEquals(oddVector.getString("skReader"), keys.skReader.toHex())
    assertEquals(oddVector.getString("skDevice"), keys.skDevice.toHex())
  }

  @Test
  fun `wipe overwrites both keys with zeros`() {
    val keys = deriveSessionKeys(sharedSecret, vectors.getString("sessionTranscriptBytes").hexToBytes())

    keys.wipe()

    assertEquals("00".repeat(32), keys.skReader.toHex())
    assertEquals("00".repeat(32), keys.skDevice.toHex())
  }
}
