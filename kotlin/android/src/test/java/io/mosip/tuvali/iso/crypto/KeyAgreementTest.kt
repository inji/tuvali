package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** ECKA-DH between EDeviceKey.Priv and EReaderKey.Pub: ISO 18013-5 clause 9.1.1.5. */
class KeyAgreementTest {

  private val vectors = SessionTestVectors.session
  private val deviceKey = EphemeralDeviceKey.fromPrivateScalar(
    vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes()
  )

  @Test
  fun `sharedSecret reproduces the ZAB of the test vectors`() {
    val readerKey = ReaderPublicKey.fromCoseKey(vectors.getJSONObject("readerKey").getString("coseKey").hexToBytes())

    val secret = sharedSecret(deviceKey, readerKey)

    assertEquals(vectors.getString("sharedSecret"), secret.toHex())
  }

  @Test
  fun `sharedSecret stays 32 bytes when it starts with a zero byte`() {
    val shortSecretVector = vectors.getJSONObject("shortSharedSecret")
    // a fixed reader key whose shared secret with the device key starts with 0x00
    val readerKey = ReaderPublicKey.fromCoseKey(shortSecretVector.getString("readerCoseKey").hexToBytes())

    val secret = sharedSecret(deviceKey, readerKey)

    assertEquals(32, secret.size)
    assertEquals(shortSecretVector.getString("sharedSecret"), secret.toHex())
  }

  @Test
  fun `sharedSecret throws IllegalStateException when the device key was wiped`() {
    val readerKey = ReaderPublicKey.fromCoseKey(vectors.getJSONObject("readerKey").getString("coseKey").hexToBytes())
    val wipedKey = EphemeralDeviceKey.generate().also { it.wipe() }

    assertThrows(IllegalStateException::class.java) { sharedSecret(wipedKey, readerKey) }
  }
}
