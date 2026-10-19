package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.asLong
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.integerKeys
import io.mosip.tuvali.testutils.toHex
import io.mosip.tuvali.testutils.valueAt
import org.junit.Assert.assertEquals
import org.junit.Test

/** EDeviceKey as COSE_Key: ISO 18013-5 clauses 9.1.1.4 and 9.1.5.2. */
class CoseKeyTest {

  private val deviceKeyVector = SessionTestVectors.session.getJSONObject("deviceKey")
  private val shortYCoordinateVector = SessionTestVectors.session.getJSONObject("shortYCoordinateKey")
  private val shortXCoordinateVector = SessionTestVectors.session.getJSONObject("shortXCoordinateKey")

  @Test
  fun `toCoseKey reproduces the device COSE key bytes of the test vectors`() {
    val key = EphemeralDeviceKey.fromPrivateScalar(deviceKeyVector.getString("privateScalar").hexToBytes())

    val coseKey = key.toCoseKey()

    assertEquals(deviceKeyVector.getString("coseKey"), encodeCbor(coseKey).toHex())
  }

  @Test
  fun `toCoseKey reproduces the test vector when x needs a leading zero byte`() {
    val key = EphemeralDeviceKey.fromPrivateScalar(shortXCoordinateVector.getString("privateScalar").hexToBytes())

    val coseKey = key.toCoseKey()

    assertEquals(shortXCoordinateVector.getString("coseKey"), encodeCbor(coseKey).toHex())
  }

  @Test
  fun `toCoseKey reproduces the test vector when y needs a leading zero byte`() {
    val key = EphemeralDeviceKey.fromPrivateScalar(shortYCoordinateVector.getString("privateScalar").hexToBytes())

    val coseKey = key.toCoseKey()

    assertEquals(shortYCoordinateVector.getString("coseKey"), encodeCbor(coseKey).toHex())
  }

  @Test
  fun `toCoseKey contains only kty, crv, x and y in that order`() {
    val key = EphemeralDeviceKey.generate()

    val coseKey = key.toCoseKey()

    // COSE labels: kty 1, crv -1, x -2, y -3
    assertEquals(listOf(1L, -1L, -2L, -3L), coseKey.integerKeys())
  }

  @Test
  fun `toCoseKey declares key type EC2 on curve P-256`() {
    val key = EphemeralDeviceKey.generate()

    val coseKey = key.toCoseKey()

    // IANA COSE registry: kty EC2 = 2, crv P-256 = 1
    assertEquals(2L, coseKey.valueAt(1).asLong())
    assertEquals(1L, coseKey.valueAt(-1).asLong())
  }
}
