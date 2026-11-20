package io.mosip.tuvali.wallet

import io.mosip.tuvali.common.advertisementPayload.AdvertisementPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifierDiscoveryTest {
  private val verifierKey = ByteArray(32) { it.toByte() }
  private val expectedAdvPayload = AdvertisementPayload.getAdvPayload("OVPMOSIP", verifierKey)
  private val scanResponsePayload = AdvertisementPayload.getScanRespPayload(verifierKey)

  @Test
  fun `android verifier with matching service data yields the key rebuilt from advertisement and scan response`() {
    val key = matchingVerifierKey(expectedAdvPayload, scanResponsePayload, null, expectedAdvPayload, "OVPMOSIP", verifierKey)

    assertArrayEquals(verifierKey, key)
  }

  @Test
  fun `android verifier with service data from another session is ignored`() {
    // same identifier, different key prefix
    val otherAdvPayload = AdvertisementPayload.getAdvPayload("OVPMOSIP", ByteArray(32) { 9 })

    val key = matchingVerifierKey(otherAdvPayload, scanResponsePayload, null, expectedAdvPayload, "OVPMOSIP", verifierKey)

    assertNull(key)
  }

  @Test
  fun `android verifier without scan response is ignored`() {
    val key = matchingVerifierKey(expectedAdvPayload, null, null, expectedAdvPayload, "OVPMOSIP", verifierKey)

    assertNull(key)
  }

  @Test
  fun `device with non matching service data is ignored even when its local name matches the qr name`() {
    val otherAdvPayload = AdvertisementPayload.getAdvPayload("OTHER", verifierKey)

    val key = matchingVerifierKey(otherAdvPayload, scanResponsePayload, "Zm9vYmFyYmF6cXV4MTIzNA", expectedAdvPayload, "Zm9vYmFyYmF6cXV4MTIzNA", verifierKey)

    assertNull(key)
  }

  @Test
  fun `ios verifier without service data whose local name matches the qr name yields the key from the qr`() {
    // per-session random name the iOS verifier puts in both its advertisement and the QR
    val sessionName = "Zm9vYmFyYmF6cXV4MTIzNA"

    val key = matchingVerifierKey(null, null, sessionName, expectedAdvPayload, sessionName, verifierKey)

    assertArrayEquals(verifierKey, key)
  }

  @Test
  fun `ios verifier from another session with a different local name is ignored`() {
    val key = matchingVerifierKey(null, null, "b3RoZXJzZXNzaW9ubmFtZTE", expectedAdvPayload, "Zm9vYmFyYmF6cXV4MTIzNA", verifierKey)

    assertNull(key)
  }

  @Test
  fun `device without service data or local name is ignored`() {
    val key = matchingVerifierKey(null, null, null, expectedAdvPayload, "Zm9vYmFyYmF6cXV4MTIzNA", verifierKey)

    assertNull(key)
  }
}
