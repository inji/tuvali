package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.engagement.DeviceEngagement
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** The BLE Ident characteristic value: ISO 18013-5 clause 8.3.3.1.1.3. */
class BleIdentTest {

  private val vectors = SessionTestVectors.session

  @Test
  fun `bleIdent reproduces the Ident of the test vectors`() {
    val eDeviceKeyBytes = vectors.getJSONObject("deviceKey").getString("eDeviceKeyBytes").hexToBytes()

    val ident = bleIdent(eDeviceKeyBytes)

    assertEquals(vectors.getString("ident"), ident.toHex())
  }

  @Test
  fun `bleIdent of a device engagement matches the Ident of the test vectors`() {
    val deviceKey = EphemeralDeviceKey.fromPrivateScalar(vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes())
    val engagement = DeviceEngagement.create(deviceKey, UUID.fromString(vectors.getString("bleClientUuid")))

    val ident = bleIdent(engagement.eDeviceKeyBytes)

    assertEquals(16, ident.size)
    assertEquals(vectors.getString("ident"), ident.toHex())
  }
}
