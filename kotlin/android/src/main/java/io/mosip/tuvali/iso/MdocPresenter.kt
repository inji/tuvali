package io.mosip.tuvali.iso

import android.content.Context
import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.ble.central.coroutine.SuspendingGattClient
import io.mosip.tuvali.iso.crypto.bleIdent
import io.mosip.tuvali.iso.engagement.DeviceEngagement
import io.mosip.tuvali.iso.engagement.RetrievalMethod
import io.mosip.tuvali.iso.engagement.toQrText
import io.mosip.tuvali.iso.exception.BleScanFailedException
import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.iso.exception.GattException
import io.mosip.tuvali.iso.exception.InvalidReaderKeyException
import io.mosip.tuvali.iso.exception.IsoTransportException
import io.mosip.tuvali.iso.exception.SessionDecryptionException
import io.mosip.tuvali.iso.exception.SessionProtocolException
import io.mosip.tuvali.iso.readerauth.ReaderAuthenticator
import io.mosip.tuvali.iso.readerauth.ReaderTrust
import io.mosip.tuvali.iso.session.DeviceRequest
import io.mosip.tuvali.iso.session.MdocSession
import io.mosip.tuvali.iso.session.SessionEvent
import io.mosip.tuvali.iso.transport.MdocTransport
import io.mosip.tuvali.iso.transport.ble.BleCentralTransport
import io.mosip.tuvali.iso.transport.ble.BleReaderFinder
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

// how long the cleanup may spend telling the reader and closing the connection
private val CLEANUP_STEP_TIMEOUT = 2.seconds

/**
 * Presents mdoc credentials to an ISO 18013-5 reader in person: shows the QR code, waits for the
 * reader to connect, hands each request to the wallet for consent, and sends the signed answer.
 *
 * One presentation runs at a time. Every ending closes the connection and destroys the session
 * keys. The reader is told the session ends (status 20) only while it is still live: not after it
 * ended the session itself, and not after Tuvali already sent it an error status.
 */
class MdocPresenter internal constructor(
  private val createEngagement: () -> DeviceEngagement,
  private val createTransport: (DeviceEngagement) -> MdocTransport,
  private val now: () -> Date,
) {
  constructor(context: Context) : this(
    createEngagement = { DeviceEngagement.create() },
    createTransport = { engagement -> transportFor(context, engagement) },
    now = { Date() },
  )

  private val running = AtomicReference<Deferred<MdocResult>?>(null)

  /**
   * Runs one presentation and returns how it ended. Throws IllegalStateException if another one is
   * running, and IllegalArgumentException if a trusted reader root is not a DER certificate.
   */
  suspend fun present(options: MdocPresentationOptions, callbacks: MdocCallbacks): MdocResult {
    val trustedRoots = parseCertificates(options.trustedReaderRoots)
    return coroutineScope {
      val presentation = async(start = CoroutineStart.LAZY) {
        runPresentation(options, callbacks, trustedRoots)
      }
      if (!running.compareAndSet(null, presentation)) {
        presentation.cancel()
        throw IllegalStateException("A presentation is already running")
      }
      try {
        presentation.await()
      } catch (cancelled: CancellationException) {
        // cancel() cancels only the presentation; if our caller was cancelled, pass that on
        if (presentation.isCancelled && isActive) MdocResult.Cancelled else throw cancelled
      } finally {
        running.compareAndSet(presentation, null)
      }
    }
  }

  /** Ends the running presentation, if any; it then returns [MdocResult.Cancelled]. */
  fun cancel() {
    running.get()?.cancel()
  }

  /** What has happened so far, to decide how the presentation ends. */
  private class Progress {
    var connected = false
    var sessionEnded = false
    var answered = false
  }

  private suspend fun runPresentation(
    options: MdocPresentationOptions,
    callbacks: MdocCallbacks,
    trustedRoots: List<X509Certificate>,
  ): MdocResult {
    val engagement = createEngagement()
    val transport = try {
      createTransport(engagement)
    } catch (failure: Exception) {
      // nothing was opened yet; only the fresh key needs destroying
      engagement.deviceKey.wipe()
      return MdocResult.Failed(MdocFailure.INTERNAL_ERROR, failure.message)
    }
    val session = MdocSession(transport, engagement, readerAuthenticator = ReaderAuthenticator(trustedRoots, now))
    val progress = Progress()
    return try {
      exchange(engagement, transport, session, options, callbacks, progress)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      resultFor(failure, progress)
    } finally {
      withContext(NonCancellable) { endPresentation(session, transport, progress) }
    }
  }

  private suspend fun exchange(
    engagement: DeviceEngagement,
    transport: MdocTransport,
    session: MdocSession,
    options: MdocPresentationOptions,
    callbacks: MdocCallbacks,
    progress: Progress,
  ): MdocResult {
    callbacks.onProgress(MdocProgress.WAITING_FOR_READER)
    callbacks.onQrReady(engagement.toQrText())
    withTimeoutOrNull(options.engagementTimeout) { transport.open() }
      ?: return MdocResult.Failed(MdocFailure.NO_READER_FOUND, "No reader connected in time")
    progress.connected = true
    callbacks.onProgress(MdocProgress.CONNECTED)

    var requestNumber = 0
    while (true) {
      val event = sessionStep(progress) {
        withTimeoutOrNull(options.inactivityTimeout) { session.receiveRequest() }
      } ?: return MdocResult.Failed(MdocFailure.TIMED_OUT, "The reader sent nothing in time")
      when (event) {
        is SessionEvent.Ended -> {
          progress.sessionEnded = true
          return if (progress.answered) MdocResult.Completed(confirmedByReader = true)
          else MdocResult.Failed(MdocFailure.READER_ENDED, "The reader ended the session without a request")
        }
        is SessionEvent.Request -> {
          requestNumber++
          callbacks.onProgress(MdocProgress.REQUEST_RECEIVED)
          val transcript = checkNotNull(session.sessionTranscriptBytes())
          val request = mdocRequest(event.deviceRequest, transcript, requestNumber)
          val decision = withTimeoutOrNull(options.inactivityTimeout) { callbacks.onRequest(request) }
            ?: return MdocResult.Failed(MdocFailure.TIMED_OUT, "The holder did not decide in time")
          when (decision) {
            is MdocSharingDecision.Send -> {
              callbacks.onProgress(MdocProgress.SENDING_RESPONSE)
              sessionStep(progress) { session.respond(decision.deviceResponse) }
              progress.answered = true
            }
            MdocSharingDecision.Decline -> {
              sessionStep(progress) { session.terminate() }
              progress.sessionEnded = true
              return MdocResult.Declined
            }
          }
        }
      }
    }
  }

  /**
   * Runs a step of the session itself. If it fails, the session has already told the reader why
   * (status 10 or 11) or the link is gone, so the cleanup must not send status 20 on top.
   */
  private inline fun <T> sessionStep(progress: Progress, step: () -> T): T = try {
    step()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    progress.sessionEnded = true
    throw failure
  }

  private suspend fun endPresentation(session: MdocSession, transport: MdocTransport, progress: Progress) {
    if (progress.connected && !progress.sessionEnded) {
      runCatching { withTimeout(CLEANUP_STEP_TIMEOUT) { session.terminate() } }
    }
    runCatching { withTimeout(CLEANUP_STEP_TIMEOUT) { transport.close() } }
    session.wipe()
  }

  private fun resultFor(failure: Exception, progress: Progress): MdocResult {
    val detail = failure.message
    return when (failure) {
      is GattDisconnectedException -> when {
        progress.answered -> MdocResult.Completed(confirmedByReader = false)
        failure.reason == LinkLossReason.BLUETOOTH_TURNED_OFF ->
          MdocResult.Failed(MdocFailure.BLUETOOTH_OFF, detail)
        else -> MdocResult.Failed(MdocFailure.CONNECTION_LOST, detail)
      }
      is BleScanFailedException, is GattException -> MdocResult.Failed(MdocFailure.CONNECTION_FAILED, detail)
      is IsoTransportException, is CborDecodingException, is SessionProtocolException ->
        MdocResult.Failed(MdocFailure.REQUEST_UNREADABLE, detail)
      is InvalidReaderKeyException, is SessionDecryptionException ->
        MdocResult.Failed(MdocFailure.READER_SECURITY_ERROR, detail)
      else -> MdocResult.Failed(MdocFailure.INTERNAL_ERROR, detail)
    }
  }

  private fun mdocRequest(
    deviceRequest: DeviceRequest,
    sessionTranscript: ByteArray,
    requestNumber: Int,
  ) = MdocRequest(
    documents = deviceRequest.docRequests.map { docRequest ->
      RequestedDocument(docRequest.docType, docRequest.nameSpaces, trustStatus(docRequest.readerTrust))
    },
    sessionTranscript = sessionTranscript,
    requestNumber = requestNumber,
  )

  private fun trustStatus(trust: ReaderTrust): ReaderTrustStatus = when (trust) {
    ReaderTrust.NotSigned -> ReaderTrustStatus.NotSigned
    is ReaderTrust.Verified -> ReaderTrustStatus.Verified(trust.readerName)
    is ReaderTrust.Unverified -> ReaderTrustStatus.Unverified(trust.readerName, trust.reason)
    is ReaderTrust.Invalid -> ReaderTrustStatus.Invalid(trust.reason)
  }

  private fun parseCertificates(derCertificates: List<ByteArray>): List<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")
    return derCertificates.map { der ->
      try {
        factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
      } catch (invalid: CertificateException) {
        throw IllegalArgumentException("A trusted reader root is not a DER certificate", invalid)
      }
    }
  }
}

/** The transport for the engagement's retrieval method: BLE with the mdoc as central client today. */
private fun transportFor(context: Context, engagement: DeviceEngagement): MdocTransport =
  when (val method = engagement.retrievalMethods.single()) {
    is RetrievalMethod.BleCentralClient -> BleCentralTransport(
      serviceUuid = method.uuid,
      expectedIdent = bleIdent(engagement.eDeviceKeyBytes),
      readerFinder = BleReaderFinder(context),
      clientFactory = { SuspendingGattClient(context) },
    )
  }
