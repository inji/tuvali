package io.mosip.tuvali.iso.crypto

import co.nstant.`in`.cbor.model.SimpleValue
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.untag24
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.items
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/** SessionTranscript for QR engagement: ISO 18013-5 clause 9.1.5.1. */
class SessionTranscriptTest {

  private val vectors = SessionTestVectors.session
  private val deviceEngagementBytes = vectors.getString("deviceEngagement").hexToBytes()
  private val readerCoseKey = untag24(decodeCbor(vectors.getJSONObject("readerKey").getString("eReaderKeyBytes").hexToBytes()))

  @Test
  fun `sessionTranscriptBytes reproduces the transcript of the test vectors`() {
    val transcript = sessionTranscriptBytes(deviceEngagementBytes, readerCoseKey)

    assertEquals(vectors.getString("sessionTranscriptBytes"), transcript.toHex())
  }

  @Test
  fun `sessionTranscriptBytes keeps the reader key bytes exactly as received when they are not canonical`() {
    val oddVector = vectors.getJSONObject("nonCanonicalReaderKey")
    // kty written as 18 02 (two bytes) instead of the shortest form 02
    val oddReaderCoseKey = untag24(decodeCbor(oddVector.getString("eReaderKeyBytes").hexToBytes()))

    val transcript = sessionTranscriptBytes(deviceEngagementBytes, oddReaderCoseKey)

    assertEquals(oddVector.getString("sessionTranscriptBytes"), transcript.toHex())
  }

  @Test
  fun `sessionTranscriptBytes holds the engagement, the reader key and a null QR handover, each as sent`() {
    val transcript = sessionTranscriptBytes(deviceEngagementBytes, readerCoseKey)

    val items = decodeCbor(untag24(decodeCbor(transcript))).items()
    assertEquals(3, items.size)
    assertEquals(deviceEngagementBytes.toHex(), untag24(items[0]).toHex())
    assertEquals(readerCoseKey.toHex(), untag24(items[1]).toHex())
    assertEquals(SimpleValue.NULL, items[2])
  }
}
