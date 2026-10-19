package io.mosip.tuvali.iso.engagement

import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.crypto.EphemeralDeviceKey
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.asBytes
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.items
import io.mosip.tuvali.testutils.toHex
import io.mosip.tuvali.testutils.valueAt
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEngagementTest {

  private val vectors = SessionTestVectors.session
  private val vectorKey = EphemeralDeviceKey.fromPrivateScalar(
    vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes()
  )
  private val vectorUuid = UUID.fromString(vectors.getString("bleClientUuid"))

  @Test
  fun `create reproduces the device engagement bytes of the test vectors`() {
    val engagement = DeviceEngagement.create(vectorKey, vectorUuid)

    assertEquals(vectors.getString("deviceEngagement"), engagement.bytes.toHex())
  }

  @Test
  fun `qrText reproduces the QR text of the test vectors`() {
    val engagement = DeviceEngagement.create(vectorKey, vectorUuid)

    assertEquals(vectors.getString("qrText"), engagement.qrText)
  }

  @Test
  fun `eDeviceKeyBytes reproduces the tag 24 wrapped COSE key of the test vectors`() {
    val engagement = DeviceEngagement.create(vectorKey, vectorUuid)

    assertEquals(vectors.getJSONObject("deviceKey").getString("eDeviceKeyBytes"), engagement.eDeviceKeyBytes.toHex())
  }

  @Test
  fun `qrText is the mdoc scheme followed by the engagement bytes in base64url without padding`() {
    val engagement = DeviceEngagement.create()

    val qrText = engagement.qrText

    assertTrue(qrText.startsWith("mdoc:"))
    val encodedPart = qrText.removePrefix("mdoc:")
    assertFalse(encodedPart.contains('=') || encodedPart.contains('+') || encodedPart.contains('/'))
    assertEquals(engagement.bytes.toHex(), Base64.getUrlDecoder().decode(encodedPart).toHex())
  }

  @Test
  fun `create encodes the BLE client UUID as 16 bytes in RFC 4122 byte order`() {
    val engagement = DeviceEngagement.create(vectorKey, vectorUuid)

    val bleOptions = decodeCbor(engagement.bytes).valueAt(2).items()[0].items()[2]

    // the UUID's hex digits in the order they are written, without dashes
    assertEquals(vectorUuid.toString().replace("-", ""), bleOptions.valueAt(11).asBytes().toHex())
  }

  @Test
  fun `create generates a random version 4 UUID of the RFC 4122 variant`() {
    val engagement = DeviceEngagement.create()

    val uuid = engagement.bleClientUuid

    assertEquals(4, uuid.version())
    // java.util.UUID reports the RFC 4122 ('10x') variant as 2
    assertEquals(2, uuid.variant())
  }

  @Test
  fun `create generates a fresh key and UUID for every session`() {
    val first = DeviceEngagement.create()

    val second = DeviceEngagement.create()

    assertNotEquals(first.bleClientUuid, second.bleClientUuid)
    assertNotEquals(first.deviceKey.x.toHex(), second.deviceKey.x.toHex())
  }

  @Test
  fun `bytes returns a copy that callers cannot change`() {
    val engagement = DeviceEngagement.create()
    val original = engagement.bytes.toHex()

    engagement.bytes[0] = 0

    assertEquals(original, engagement.bytes.toHex())
  }

  @Test
  fun `eDeviceKeyBytes returns a copy that callers cannot change`() {
    val engagement = DeviceEngagement.create()
    val original = engagement.eDeviceKeyBytes.toHex()

    engagement.eDeviceKeyBytes[0] = 0

    assertEquals(original, engagement.eDeviceKeyBytes.toHex())
  }
}
