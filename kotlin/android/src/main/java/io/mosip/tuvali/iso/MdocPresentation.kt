package io.mosip.tuvali.iso

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a presentation runs. [trustedReaderRoots] are DER-encoded root certificates for reader
 * authentication; with none, every validly signed request is reported as unverified.
 */
data class MdocPresentationOptions(
  val trustedReaderRoots: List<ByteArray> = emptyList(),
  // clause 8.2.3 asks readers to wait at least 30 s for the engagement
  val engagementTimeout: Duration = 60.seconds,
  // clause 9.1.1.4: no-activity time-out of at least 300 s
  val inactivityTimeout: Duration = 300.seconds,
)

/** What the wallet is told and asked during a presentation. */
interface MdocCallbacks {
  /** Show this text as a QR code (ISO/IEC 18004) for the reader to scan. */
  suspend fun onQrReady(qrText: String)

  /** Ask the holder about the reader's request and return their decision with the signed answer. */
  suspend fun onRequest(request: MdocRequest): MdocSharingDecision

  fun onProgress(progress: MdocProgress) = Unit
}

/** The holder's decision on one request. */
sealed class MdocSharingDecision {
  /** Send this DeviceResponse, built and signed by the wallet for the approved fields. */
  class Send(val deviceResponse: ByteArray) : MdocSharingDecision()

  /** Share nothing; the session ends with status 20. */
  object Decline : MdocSharingDecision()
}

/**
 * One request from the reader. [requestNumber] is 1 for the first request and counts further ones
 * in the same session. [sessionTranscript] are the bytes the DeviceResponse must be signed over.
 */
class MdocRequest(
  val documents: List<RequestedDocument>,
  val sessionTranscript: ByteArray,
  val requestNumber: Int,
)

/**
 * One requested document: its type, the requested data elements per namespace with whether the
 * reader intends to keep each one, and how far the reader could be trusted.
 */
class RequestedDocument(
  val docType: String,
  val nameSpaces: Map<String, Map<String, Boolean>>,
  val readerTrust: ReaderTrustStatus,
)

/** The result of checking the reader's signature on a document request. */
sealed class ReaderTrustStatus {
  object NotSigned : ReaderTrustStatus()
  class Verified(val readerName: String?) : ReaderTrustStatus()
  class Unverified(val readerName: String?, val reason: String) : ReaderTrustStatus()
  class Invalid(val reason: String) : ReaderTrustStatus()
}

enum class MdocProgress { WAITING_FOR_READER, CONNECTED, REQUEST_RECEIVED, SENDING_RESPONSE }

/** How a presentation ended. */
sealed class MdocResult {
  /**
   * At least one answer was sent. [confirmedByReader] is false when the link dropped after the
   * answer instead of the reader ending the session.
   */
  class Completed(val confirmedByReader: Boolean) : MdocResult()

  object Declined : MdocResult()

  object Cancelled : MdocResult()

  class Failed(val failure: MdocFailure, val detail: String?) : MdocResult()
}

enum class MdocFailure {
  BLUETOOTH_OFF,
  NO_READER_FOUND,
  CONNECTION_FAILED,
  CONNECTION_LOST,
  TIMED_OUT,
  READER_ENDED,
  REQUEST_UNREADABLE,
  READER_SECURITY_ERROR,
  INTERNAL_ERROR,
}
