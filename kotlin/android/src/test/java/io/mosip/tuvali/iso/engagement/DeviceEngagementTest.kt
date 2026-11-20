package io.mosip.tuvali.iso.engagement

import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.crypto.EphemeralDeviceKey
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.asBytes
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.integerKeys
import io.mosip.tuvali.testutils.items
import io.mosip.tuvali.testutils.toHex
import io.mosip.tuvali.testutils.valueAt
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceEngagementTest {

  private val vectors = SessionTestVectors.session
  private val vectorKey = EphemeralDeviceKey.fromPrivateScalar(
    vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes()
  )
  private val vectorUuid = UUID.fromString(vectors.getString("bleClientUuid"))

  @Test
  fun `create reproduces the device engagement bytes of the test vectors`() {
    val engagement = DeviceEngagement.create(vectorKey, listOf(RetrievalMethod.BleCentralClient(vectorUuid)))

    assertEquals(vectors.getString("deviceEngagement"), engagement.bytes.toHex())
  }

  @Test
  fun `eDeviceKeyBytes reproduces the tag 24 wrapped COSE key of the test vectors`() {
    val engagement = DeviceEngagement.create(vectorKey, listOf(RetrievalMethod.BleCentralClient(vectorUuid)))

    assertEquals(vectors.getJSONObject("deviceKey").getString("eDeviceKeyBytes"), engagement.eDeviceKeyBytes.toHex())
  }

  @Test
  fun `create encodes the BLE client UUID as 16 bytes in RFC 4122 byte order`() {
    val engagement = DeviceEngagement.create(vectorKey, listOf(RetrievalMethod.BleCentralClient(vectorUuid)))

    val bleOptions = decodeCbor(engagement.bytes).valueAt(2).items()[0].items()[2]

    // the UUID's hex digits in the order they are written, without dashes
    assertEquals(vectorUuid.toString().replace("-", ""), bleOptions.valueAt(11).asBytes().toHex())
  }

  @Test
  fun `create generates a random version 4 UUID of the RFC 4122 variant`() {
    val engagement = DeviceEngagement.create()

    val uuid = engagement.bleCentralClientUuid()

    assertEquals(4, uuid.version())
    // java.util.UUID reports the RFC 4122 ('10x') variant as 2
    assertEquals(2, uuid.variant())
  }

  @Test
  fun `create generates a fresh key and UUID for every session`() {
    val first = DeviceEngagement.create()

    val second = DeviceEngagement.create()

    assertNotEquals(first.bleCentralClientUuid(), second.bleCentralClientUuid())
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

  @Test
  fun `bytes leave out DeviceRetrievalMethods when no retrieval method is offered`() {
    // NFC engagement sends no retrieval methods in the engagement (clause 8.2.1.1)
    val noMethods = emptyList<RetrievalMethod>()

    val engagement = DeviceEngagement.create(vectorKey, noMethods)

    assertEquals(listOf(0L, 1L), decodeCbor(engagement.bytes).integerKeys())
  }

  private fun DeviceEngagement.bleCentralClientUuid(): UUID =
    (retrievalMethods.single() as RetrievalMethod.BleCentralClient).uuid
}
