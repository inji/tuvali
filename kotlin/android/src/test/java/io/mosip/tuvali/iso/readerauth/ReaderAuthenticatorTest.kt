package io.mosip.tuvali.iso.readerauth

import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Tag
import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.cborMapOf
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.items
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** mdoc reader authentication: ISO 18013-5 clause 9.1.4 and the Annex B Table B.6 profile. */
class ReaderAuthenticatorTest {

  private val vectors = SessionTestVectors.session
  private val auth = vectors.getJSONObject("readerAuth")
  private val transcript = vectors.getString("sessionTranscriptBytes").hexToBytes()
  private val mdlItemsRequest = vectors.getJSONObject("session").getString("mdlItemsRequestBytes").hexToBytes()

  private val root = certificate(auth.getString("rootCertificate"))
  private val otherRoot = certificate(auth.getString("otherRootCertificate"))
  // the test reader certificates are valid from 2026-01-01 to 2029-01-01
  private val validationTime = Date.from(Instant.parse(auth.getString("validationTime")))

  private fun authenticator(anchors: List<X509Certificate> = listOf(root), now: Date = validationTime) =
    ReaderAuthenticator(anchors) { now }

  private fun readerAuth(name: String): DataItem = decodeCbor(auth.getString(name).hexToBytes())

  // ---------- the four trust states ----------

  @Test
  fun `check returns NotSigned when the request has no readerAuth`() {
    val trust = authenticator().check(null, transcript, mdlItemsRequest)

    assertEquals(ReaderTrust.NotSigned, trust)
  }

  @Test
  fun `check returns Verified with the reader name for a signature chaining to a trusted root`() {
    val trust = authenticator().check(readerAuth("valid"), transcript, mdlItemsRequest)

    assertEquals("Tuvali Test Reader", (trust as ReaderTrust.Verified).readerName)
  }

  @Test
  fun `check returns Verified for a two-certificate chain through an intermediate CA`() {
    val trust = authenticator().check(readerAuth("twoCertChain"), transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Verified)
    assertEquals(2, (trust as ReaderTrust.Verified).certificateChain.size)
  }

  @Test
  fun `check returns Unverified for a valid signature whose root is not in the trust list`() {
    val trust = authenticator().check(readerAuth("untrustedRoot"), transcript, mdlItemsRequest)

    assertEquals("Untrusted Test Reader", (trust as ReaderTrust.Unverified).readerName)
  }

  @Test
  fun `check returns Unverified for a valid signature when no trust list is configured`() {
    val trust = authenticator(anchors = emptyList()).check(readerAuth("valid"), transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Unverified)
  }

  @Test
  fun `check returns Verified when the trust list holds the other root as well`() {
    val trust = authenticator(anchors = listOf(otherRoot, root)).check(readerAuth("valid"), transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Verified)
  }

  // ---------- signatures that must not be accepted ----------

  @Test
  fun `check returns Invalid when the signature does not verify`() {
    val tampered = rebuild(readerAuth("valid"), signature = { it.also { bytes -> bytes[0] = (bytes[0] + 1).toByte() } })

    val trust = authenticator().check(tampered, transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check returns Invalid for a signature made over a different session transcript`() {
    // a valid signature by a trusted reader, but for another session: a replay
    val trust = authenticator().check(readerAuth("otherTranscript"), transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check returns Invalid when the ItemsRequest differs from what was signed`() {
    val otherItems = vectors.getJSONObject("session").getString("secondDocItemsRequestBytes").hexToBytes()

    val trust = authenticator().check(readerAuth("valid"), transcript, otherItems)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  // ---------- the reader certificate ----------

  @Test
  fun `check returns Invalid when the reader certificate has expired`() {
    // valid from 2020-01-01 to 2021-01-01
    val trust = authenticator().check(readerAuth("expiredLeaf"), transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check returns Invalid when the reader certificate is not yet valid`() {
    val beforeValidity = Date.from(Instant.parse("2025-06-01T00:00:00Z"))

    val trust = authenticator(now = beforeValidity).check(readerAuth("valid"), transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check returns Invalid when the certificate's extended key usage is not mdoc reader authentication`() {
    // extended key usage is TLS server authentication instead of 1.0.18013.5.1.6
    val trust = authenticator().check(readerAuth("wrongEku"), transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  // ---------- the COSE_Sign1 structure ----------

  @Test
  fun `check returns Invalid for an algorithm it does not support`() {
    // COSE alg -8 is EdDSA
    val eddsa = rebuild(readerAuth("valid"), protectedHeader = encodeCbor(cborMapOf(1L to -8L)))

    val trust = authenticator().check(eddsa, transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check returns Invalid when the unprotected header has no x5chain`() {
    val noChain = rebuild(readerAuth("valid"), unprotectedHeader = cborMapOf())

    val trust = authenticator().check(noChain, transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check returns Invalid when the payload is not null`() {
    // clause 9.1.4: the payload is detached, so it must be null
    val attachedPayload = rebuild(readerAuth("valid"), payload = ByteString(byteArrayOf(1)))

    val trust = authenticator().check(attachedPayload, transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check returns Invalid when readerAuth is not a four-element array`() {
    val notCose = cborArrayOf(1L, 2L)

    val trust = authenticator().check(notCose, transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Invalid)
  }

  @Test
  fun `check accepts a COSE_Sign1 that carries the COSE_Sign1 tag`() {
    // clause 9.1.4 asks for an untagged COSE_Sign1; tag 18 is accepted as well
    val tagged = readerAuth("valid").apply { setTag(Tag(18)) }

    val trust = authenticator().check(tagged, transcript, mdlItemsRequest)

    assertTrue(trust is ReaderTrust.Verified)
  }

  private fun certificate(hex: String): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    return factory.generateCertificate(ByteArrayInputStream(hex.hexToBytes())) as X509Certificate
  }

  /** Rebuilds a COSE_Sign1 with some parts replaced, to test one fault at a time. */
  private fun rebuild(
    original: DataItem,
    protectedHeader: ByteArray? = null,
    unprotectedHeader: DataItem? = null,
    payload: DataItem? = null,
    signature: ((ByteArray) -> ByteArray)? = null,
  ): DataItem {
    val parts = original.items()
    return cborArrayOf(
      protectedHeader ?: (parts[0] as ByteString).bytes,
      unprotectedHeader ?: parts[1],
      payload ?: parts[2],
      (parts[3] as ByteString).bytes.copyOf().let { signature?.invoke(it) ?: it },
    )
  }
}
