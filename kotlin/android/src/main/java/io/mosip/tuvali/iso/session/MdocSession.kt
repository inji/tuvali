package io.mosip.tuvali.iso.session

import io.mosip.tuvali.iso.crypto.Handover
import io.mosip.tuvali.iso.crypto.SessionEncryption
import io.mosip.tuvali.iso.crypto.sessionTranscriptBytes
import io.mosip.tuvali.iso.engagement.DeviceEngagement
import io.mosip.tuvali.iso.exception.SessionProtocolException
import io.mosip.tuvali.iso.readerauth.ReaderAuthenticator
import io.mosip.tuvali.iso.transport.MdocTransport
import io.mosip.tuvali.iso.transport.ReaderEvent
import kotlin.coroutines.cancellation.CancellationException

/** What the reader did: sent a request, or ended the session (with its status, if it sent one). */
internal sealed class SessionEvent {
  class Request(val deviceRequest: DeviceRequest) : SessionEvent()
  class Ended(val status: Int?) : SessionEvent()
}

/**
 * One ISO 18013-5 session over any [MdocTransport] (clause 9.1.1.4 steps 2 to 4).
 *
 * The first message must be a SessionEstablishment, which sets up session encryption from the
 * reader's key; later requests come in SessionData. When a message cannot be used, the reader is
 * told why before the error is thrown: status 10 for a bad reader key, a message that does not
 * decrypt, or an attempt to re-establish the session (it is never re-keyed); status 11 for
 * anything that is not the expected CBOR or comes in the wrong order. Each requested document
 * carries the result of checking its reader authentication with [readerAuthenticator]. Whether to
 * answer a further request is the caller's decision.
 */
internal class MdocSession(
  private val transport: MdocTransport,
  private val engagement: DeviceEngagement,
  private val handover: Handover = Handover.Qr,
  private val readerAuthenticator: ReaderAuthenticator = ReaderAuthenticator(),
) {
  private var encryption: SessionEncryption? = null
  private var sessionTranscript: ByteArray? = null

  /** Waits for the reader's next request, or for it to end the session. */
  suspend fun receiveRequest(): SessionEvent {
    val message = when (val event = transport.receive()) {
      ReaderEvent.Ended -> return SessionEvent.Ended(status = null)
      is ReaderEvent.Message -> event.bytes
    }

    val received = tellReaderOnFailure(SessionStatus.CBOR_DECODING_ERROR) { SessionMessage.decode(message) }
    val encryptedRequest = when (received) {
      is SessionMessage.Establishment -> {
        if (encryption != null) {
          rejectOnProtocolViolation(SessionStatus.ENCRYPTION_ERROR, "Reader tried to re-establish the session")
        }
        establishSession(received)
      }
      is SessionMessage.Data -> {
        if (encryption == null) {
          rejectOnProtocolViolation(SessionStatus.CBOR_DECODING_ERROR, "First message must be a SessionEstablishment")
        }
        received.status?.let { status -> return SessionEvent.Ended(status) }
        received.data
          ?: rejectOnProtocolViolation(SessionStatus.CBOR_DECODING_ERROR, "SessionData has neither data nor status")
      }
    }

    val plaintext = tellReaderOnFailure(SessionStatus.ENCRYPTION_ERROR) {
      checkNotNull(encryption).decryptFromReader(encryptedRequest)
    }
    val transcript = checkNotNull(sessionTranscript)
    val request = tellReaderOnFailure(SessionStatus.CBOR_DECODING_ERROR) {
      DeviceRequest.decode(plaintext) { readerAuth, itemsRequestBytes ->
        readerAuthenticator.check(readerAuth, transcript, itemsRequestBytes)
      }
    }
    return SessionEvent.Request(request)
  }

  /** Sends the encrypted DeviceResponse (built and signed outside Tuvali). */
  suspend fun respond(deviceResponse: ByteArray) {
    val sessionEncryption = checkNotNull(encryption) { "No request has been received" }
    transport.send(SessionMessage.Data(data = sessionEncryption.encryptForReader(deviceResponse)).encode())
  }

  /** Tells the reader the mdoc ends the session (status 20). */
  suspend fun terminate() {
    transport.send(SessionMessage.Data(status = SessionStatus.SESSION_TERMINATION).encode())
  }

  /** Destroys the session keys and the ephemeral device key (clause 9.1.1.4 step 4). */
  fun wipe() {
    encryption?.wipe()
    engagement.deviceKey.wipe()
  }

  private suspend fun establishSession(establishment: SessionMessage.Establishment): ByteArray {
    val transcript = sessionTranscriptBytes(engagement.bytes, establishment.readerCoseKey, handover)
    encryption = tellReaderOnFailure(SessionStatus.ENCRYPTION_ERROR) {
      SessionEncryption.establish(engagement.deviceKey, establishment.readerCoseKey, transcript)
    }
    sessionTranscript = transcript
    return establishment.data
  }

  /** The reader broke the session order: tell it [status] (best effort) and end the session with [reason]. */
  private suspend fun rejectOnProtocolViolation(status: Int, reason: String): Nothing {
    sendStatusBestEffortToReader(status)
    throw SessionProtocolException(reason)
  }

  private suspend fun sendStatusBestEffortToReader(status: Int) {
    runCatching { transport.send(SessionMessage.Data(status = status).encode()) }
  }

  /** Runs [step]; if it fails, sends [status] to the reader ( best effort) and rethrows. */
  private suspend inline fun <T> tellReaderOnFailure(status: Int, step: () -> T): T = try {
    step()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    sendStatusBestEffortToReader(status)
    throw failure
  }
}
