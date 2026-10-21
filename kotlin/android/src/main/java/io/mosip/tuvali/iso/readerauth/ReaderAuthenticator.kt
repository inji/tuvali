package io.mosip.tuvali.iso.readerauth

import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.Number as CborNumber
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import io.mosip.tuvali.iso.cbor.asArray
import io.mosip.tuvali.iso.cbor.asBytes
import io.mosip.tuvali.iso.cbor.asMap
import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.tag24
import io.mosip.tuvali.iso.cbor.untag24
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Date
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle

// COSE header labels (IANA COSE registry): alg in the protected header, x5chain in the unprotected one
private const val COSE_HEADER_ALG = 1L
private const val COSE_HEADER_X5CHAIN = 33L

// COSE algorithms ES256, ES384 and ES512 (clause 9.1.4), as JCA signature names
private val SUPPORTED_ALGORITHMS = mapOf(-7L to "SHA256withECDSA", -35L to "SHA384withECDSA", -36L to "SHA512withECDSA")

// Annex B Table B.6
private const val EKU_MDL_READER_AUTH = "1.0.18013.5.1.6"
private const val KEY_USAGE_DIGITAL_SIGNATURE = 0

private const val COSE_SIGN1_ELEMENTS = 4

/**
 * Checks a DocRequest's readerAuth (clause 9.1.4): an untagged COSE_Sign1 with a detached payload,
 * signed over ReaderAuthenticationBytes = #6.24(["ReaderAuthentication", SessionTranscript,
 * ItemsRequestBytes]) with ES256, ES384 or ES512, carrying the reader certificate chain in x5chain.
 *
 * The reader certificate must be valid at [now] and, when it has key usage or extended key usage,
 * allow digital signatures for mdoc reader authentication (Annex B Table B.6). The chain is then
 * validated against [trustAnchors] (no revocation checks yet). Problems never throw: they are
 * reported as [ReaderTrust.Invalid], so the holder can still be told who is asking.
 */
internal class ReaderAuthenticator(
  private val trustAnchors: List<X509Certificate> = emptyList(),
  private val now: () -> Date = { Date() },
) {
  fun check(readerAuth: DataItem?, sessionTranscriptBytes: ByteArray, itemsRequestBytes: ByteArray): ReaderTrust {
    if (readerAuth == null) return ReaderTrust.NotSigned
    return try {
      checkSigned(readerAuth, sessionTranscriptBytes, itemsRequestBytes)
    } catch (rejected: ReaderAuthRejected) {
      ReaderTrust.Invalid(rejected.reason)
    } catch (malformed: Exception) {
      ReaderTrust.Invalid("readerAuth could not be read: ${malformed.message}")
    }
  }

  private fun checkSigned(
    readerAuth: DataItem,
    sessionTranscriptBytes: ByteArray,
    itemsRequestBytes: ByteArray,
  ): ReaderTrust {
    val sign1 = readerAuth.asArray("readerAuth")
    if (sign1.size != COSE_SIGN1_ELEMENTS) reject("readerAuth is not a COSE_Sign1")
    val protectedBytes = sign1[0].asBytes("protected header")
    val unprotected = sign1[1].asMap("unprotected header")
    if (sign1[2] != SimpleValue.NULL) reject("readerAuth payload must be detached (null)")
    val signature = sign1[3].asBytes("signature")

    val algorithm = SUPPORTED_ALGORITHMS[algorithmOf(protectedBytes)] ?: reject("Unsupported signature algorithm")
    val chain = certificateChain(unprotected)
    val readerCertificate = chain.first()
    checkReaderCertificate(readerCertificate)

    val signedBytes = sigStructure(protectedBytes, readerAuthenticationBytes(sessionTranscriptBytes, itemsRequestBytes))
    if (!verify(algorithm, readerCertificate, signedBytes, signature)) reject("Reader signature does not verify")

    val readerName = commonNameOf(readerCertificate)
    val untrustedReason = chainProblem(chain) ?: return ReaderTrust.Verified(readerName, chain)
    return ReaderTrust.Unverified(readerName, chain, untrustedReason)
  }

  private fun algorithmOf(protectedBytes: ByteArray): Long? {
    if (protectedBytes.isEmpty()) return null
    val alg = decodeCbor(protectedBytes).asMap("protected header").get(UnsignedInteger(COSE_HEADER_ALG))
    return (alg as? CborNumber)?.value?.toLong()
  }

  /** x5chain is one certificate or an array of them, the reader's own certificate first. */
  private fun certificateChain(unprotected: CborMap): List<X509Certificate> {
    val x5chain = unprotected.get(UnsignedInteger(COSE_HEADER_X5CHAIN)) ?: reject("readerAuth has no x5chain")
    val encodedCertificates = when (x5chain) {
      is ByteString -> listOf(x5chain.bytes)
      else -> x5chain.asArray("x5chain").map { it.asBytes("certificate") }
    }
    if (encodedCertificates.isEmpty()) reject("x5chain is empty")
    val factory = CertificateFactory.getInstance("X.509")
    return encodedCertificates.map { der -> factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate }
  }

  private fun checkReaderCertificate(certificate: X509Certificate) {
    try {
      certificate.checkValidity(now())
    } catch (expired: CertificateExpiredException) {
      reject("Reader certificate has expired")
    } catch (notYetValid: CertificateNotYetValidException) {
      reject("Reader certificate is not yet valid")
    }
    certificate.keyUsage?.let { keyUsage ->
      if (!keyUsage[KEY_USAGE_DIGITAL_SIGNATURE]) reject("Reader certificate does not allow digital signatures")
    }
    certificate.extendedKeyUsage?.let { extendedKeyUsage ->
      if (EKU_MDL_READER_AUTH !in extendedKeyUsage) reject("Reader certificate is not for mdoc reader authentication")
    }
    if (certificate.publicKey !is ECPublicKey) reject("Reader certificate does not hold an EC key")
  }

  /** ReaderAuthenticationBytes, built from the transcript and ItemsRequest bytes exactly as they are. */
  private fun readerAuthenticationBytes(sessionTranscriptBytes: ByteArray, itemsRequestBytes: ByteArray): ByteArray {
    val arrayOfThree = 0x83.toByte()
    val sessionTranscript = untag24(decodeCbor(sessionTranscriptBytes))
    val readerAuthentication = byteArrayOf(arrayOfThree) +
      encodeCbor(UnicodeString("ReaderAuthentication")) +
      sessionTranscript +
      encodeCbor(tag24(itemsRequestBytes))
    return encodeCbor(tag24(readerAuthentication))
  }

  /** COSE Sig_structure for COSE_Sign1 with empty external_aad and the detached payload (RFC 8152). */
  private fun sigStructure(protectedBytes: ByteArray, detachedPayload: ByteArray): ByteArray =
    encodeCbor(cborArrayOf("Signature1", protectedBytes, ByteArray(0), detachedPayload))

  /** COSE carries ECDSA signatures as r ‖ s; Java expects them DER-encoded. */
  private fun verify(
    algorithm: String,
    certificate: X509Certificate,
    signedBytes: ByteArray,
    coseSignature: ByteArray,
  ): Boolean {
    if (coseSignature.isEmpty() || coseSignature.size % 2 != 0) return false
    val half = coseSignature.size / 2
    val r = BigInteger(1, coseSignature.copyOfRange(0, half))
    val s = BigInteger(1, coseSignature.copyOfRange(half, coseSignature.size))
    val derSignature = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded
    return Signature.getInstance(algorithm).run {
      initVerify(certificate.publicKey)
      update(signedBytes)
      verify(derSignature)
    }
  }

  /** Null when the chain leads to a trusted root; otherwise why it does not. */
  private fun chainProblem(chain: List<X509Certificate>): String? {
    if (trustAnchors.isEmpty()) return "No trusted reader roots are configured"
    // the path holds the chain without any certificate that is itself a trust anchor
    val path = chain.filterNot { certificate -> certificate in trustAnchors }
    val parameters = PKIXParameters(trustAnchors.map { TrustAnchor(it, null) }.toSet()).apply {
      isRevocationEnabled = false
      date = now()
    }
    return try {
      val certificatePath = CertificateFactory.getInstance("X.509").generateCertPath(path)
      CertPathValidator.getInstance("PKIX").validate(certificatePath, parameters)
      null
    } catch (invalid: CertPathValidatorException) {
      "Reader certificate chain does not lead to a trusted root: ${invalid.message}"
    }
  }

  private fun commonNameOf(certificate: X509Certificate): String? {
    val subject = X500Name.getInstance(certificate.subjectX500Principal.encoded)
    return subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString()
  }

  private class ReaderAuthRejected(val reason: String) : Exception(reason)

  private fun reject(reason: String): Nothing = throw ReaderAuthRejected(reason)
}
