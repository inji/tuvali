package io.mosip.tuvali.iso.engagement

import io.mosip.tuvali.iso.crypto.EphemeralDeviceKey
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** QR code content for device engagement: ISO 18013-5 clause 8.2.2.3. */
class QrEngagementTest {

  private val vectors = SessionTestVectors.session
  private val vectorKey = EphemeralDeviceKey.fromPrivateScalar(
    vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes()
  )
  private val vectorUuid = UUID.fromString(vectors.getString("bleClientUuid"))

  @Test
  fun `toQrText reproduces the QR text of the test vectors`() {
    val engagement = DeviceEngagement.create(vectorKey, listOf(RetrievalMethod.BleCentralClient(vectorUuid)))

    val qrText = engagement.toQrText()

    assertEquals(vectors.getString("qrText"), qrText)
  }

  @Test
  fun `toQrText is the mdoc scheme followed by the engagement bytes in base64url without padding`() {
    val engagement = DeviceEngagement.create()

    val qrText = engagement.toQrText()

    assertTrue(qrText.startsWith("mdoc:"))
    val encodedPart = qrText.removePrefix("mdoc:")
    assertFalse(encodedPart.contains('=') || encodedPart.contains('+') || encodedPart.contains('/'))
    assertEquals(engagement.bytes.toHex(), Base64.getUrlDecoder().decode(encodedPart).toHex())
  }

  @Test
  fun `toQrText throws IllegalArgumentException when no retrieval method is offered`() {
    // a QR engagement must list at least one device retrieval method (clause 8.2.1.1)
    val engagement = DeviceEngagement.create(vectorKey, emptyList())

    assertThrows(IllegalArgumentException::class.java) { engagement.toQrText() }
  }
}
