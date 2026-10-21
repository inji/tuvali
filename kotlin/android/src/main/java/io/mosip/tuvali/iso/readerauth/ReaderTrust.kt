package io.mosip.tuvali.iso.readerauth

import java.security.cert.X509Certificate

/** How far a document request's reader authentication (clause 9.1.4) could be trusted. */
internal sealed class ReaderTrust {
  /** The reader did not sign the request. */
  object NotSigned : ReaderTrust()

  /** The signature is valid and the reader's certificate chain leads to a trusted root. */
  class Verified(val readerName: String?, val certificateChain: List<X509Certificate>) : ReaderTrust()

  /** The signature is valid, but the chain does not lead to a trusted root (or no trust list is set). */
  class Unverified(
    val readerName: String?,
    val certificateChain: List<X509Certificate>,
    val reason: String,
  ) : ReaderTrust()

  /** The signature or the reader's certificate cannot be accepted. */
  class Invalid(val reason: String) : ReaderTrust()
}
