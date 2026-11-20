package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.asBytes
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import io.mosip.tuvali.testutils.valueAt
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralDeviceKeyTest {

  private val deviceKeyVector = SessionTestVectors.session.getJSONObject("deviceKey")
  private val shortYCoordinateVector = SessionTestVectors.session.getJSONObject("shortYCoordinateKey")
  private val shortXCoordinateVector = SessionTestVectors.session.getJSONObject("shortXCoordinateKey")

  @Test
  fun `fromPrivateScalar derives the public x and y of the test vector key`() {
    val scalar = deviceKeyVector.getString("privateScalar").hexToBytes()
    val vectorCoseKey = decodeCbor(deviceKeyVector.getString("coseKey").hexToBytes())

    val key = EphemeralDeviceKey.fromPrivateScalar(scalar)

    assertEquals(vectorCoseKey.valueAt(-2).asBytes().toHex(), key.x.toHex())
    assertEquals(vectorCoseKey.valueAt(-3).asBytes().toHex(), key.y.toHex())
  }

  @Test
  fun `y stays 32 bytes when the coordinate starts with a zero byte`() {
    // a fixed key whose y coordinate is below 2^248, so its first byte is 0x00
    val scalar = shortYCoordinateVector.getString("privateScalar").hexToBytes()
    val vectorCoseKey = decodeCbor(shortYCoordinateVector.getString("coseKey").hexToBytes())

    val key = EphemeralDeviceKey.fromPrivateScalar(scalar)

    assertEquals(32, key.y.size)
    assertEquals(vectorCoseKey.valueAt(-3).asBytes().toHex(), key.y.toHex())
  }

  @Test
  fun `x stays 32 bytes when the coordinate starts with a zero byte`() {
    // a fixed key whose x coordinate is below 2^248, so its first byte is 0x00
    val scalar = shortXCoordinateVector.getString("privateScalar").hexToBytes()
    val vectorCoseKey = decodeCbor(shortXCoordinateVector.getString("coseKey").hexToBytes())

    val key = EphemeralDeviceKey.fromPrivateScalar(scalar)

    assertEquals(32, key.x.size)
    assertEquals(vectorCoseKey.valueAt(-2).asBytes().toHex(), key.x.toHex())
  }

  @Test
  fun `generate creates a public key on the P-256 curve`() {
    val p256 = CustomNamedCurves.getByName("secp256r1").curve

    val key = EphemeralDeviceKey.generate()

    assertEquals(p256, key.publicKey.parameters.curve)
    assertTrue(key.publicKey.q.isValid)
  }

  @Test
  fun `generate creates a different key every time`() {
    val first = EphemeralDeviceKey.generate()

    val second = EphemeralDeviceKey.generate()

    assertNotEquals(first.x.toHex(), second.x.toHex())
  }

  @Test
  fun `fromPrivateScalar throws IllegalArgumentException when the scalar is zero`() {
    val zero = ByteArray(32)

    assertThrows(IllegalArgumentException::class.java) { EphemeralDeviceKey.fromPrivateScalar(zero) }
  }

  @Test
  fun `fromPrivateScalar throws IllegalArgumentException when the scalar is not below the curve order`() {
    // the P-256 group order n itself
    val curveOrder = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551".hexToBytes()

    assertThrows(IllegalArgumentException::class.java) { EphemeralDeviceKey.fromPrivateScalar(curveOrder) }
  }

  @Test
  fun `x returns a copy that callers cannot change`() {
    val key = EphemeralDeviceKey.generate()
    val original = key.x.toHex()

    key.x[0] = (key.x[0] + 1).toByte()

    assertEquals(original, key.x.toHex())
  }

  @Test
  fun `requirePrivateKey throws IllegalStateException after wipe`() {
    val key = EphemeralDeviceKey.generate()

    key.wipe()

    assertThrows(IllegalStateException::class.java) { key.requirePrivateKey() }
  }

  @Test
  fun `wipe can be called again without failing`() {
    val key = EphemeralDeviceKey.generate()
    key.wipe()

    key.wipe()

    assertThrows(IllegalStateException::class.java) { key.requirePrivateKey() }
  }
}
