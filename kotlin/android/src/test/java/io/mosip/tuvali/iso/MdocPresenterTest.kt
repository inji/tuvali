package io.mosip.tuvali.iso

import io.mosip.tuvali.iso.crypto.EphemeralDeviceKey
import io.mosip.tuvali.iso.engagement.DeviceEngagement
import io.mosip.tuvali.iso.engagement.RetrievalMethod
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.iso.transport.ReaderEvent
import io.mosip.tuvali.testutils.ScriptedTransport
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The public presentation flow: engagement, request, consent, answer and every way it can end. */
@OptIn(ExperimentalCoroutinesApi::class)
class MdocPresenterTest {

  private val vectors = SessionTestVectors.session
  private val messages = vectors.getJSONObject("session")
  private val auth = vectors.getJSONObject("readerAuth")

  // the fixed engagement of the test vectors, so the scripted reader's messages decrypt
  private val deviceKey = EphemeralDeviceKey.fromPrivateScalar(
    vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes()
  )
  private val engagement = DeviceEngagement.create(
    deviceKey,
    listOf(RetrievalMethod.BleCentralClient(UUID.fromString(vectors.getString("bleClientUuid")))),
  )
  private val transport = ScriptedTransport()
  private val validationTime = Date.from(Instant.parse(auth.getString("validationTime")))
  private val presenter = MdocPresenter({ engagement }, { transport }, { validationTime })
  private val options = MdocPresentationOptions(engagementTimeout = 60.seconds, inactivityTimeout = 300.seconds)

  private fun message(name: String) = ReaderEvent.Message(messages.getString(name).hexToBytes())
  private val deviceResponse = messages.getString("deviceResponse").hexToBytes()

  /** Records what the presenter told the wallet, and answers every request with [decide]. */
  private inner class RecordingCallbacks(
    private val decide: suspend (MdocRequest) -> MdocSharingDecision = { MdocSharingDecision.Send(deviceResponse) },
  ) : MdocCallbacks {
    val qrTexts = mutableListOf<String>()
    val requests = mutableListOf<MdocRequest>()
    val progress = mutableListOf<MdocProgress>()

    override suspend fun onQrReady(qrText: String) {
      qrTexts += qrText
    }

    override suspend fun onRequest(request: MdocRequest): MdocSharingDecision {
      requests += request
      return decide(request)
    }

    override fun onProgress(progress: MdocProgress) {
      this.progress += progress
    }
  }

  private fun TestScope.startPresenting(callbacks: MdocCallbacks) =
    async { presenter.present(options, callbacks) }.also { runCurrent() }

  // ---------- the normal flow ----------

  @Test
  fun `present shows the QR text of the engagement`() = runTest {
    val callbacks = RecordingCallbacks()
    transport.queue(ReaderEvent.Ended)

    presenter.present(options, callbacks)

    assertEquals(listOf(vectors.getString("qrText")), callbacks.qrTexts)
  }

  @Test
  fun `present hands every requested document, its fields and the session transcript to onRequest`() = runTest {
    val callbacks = RecordingCallbacks()
    transport.queue(message("sessionEstablishment"))
    transport.queue(ReaderEvent.Ended)

    presenter.present(options, callbacks)

    val request = callbacks.requests.single()
    assertEquals(listOf("org.iso.18013.5.1.mDL", "org.example.test.1"), request.documents.map { it.docType })
    val mdlElements = request.documents[0].nameSpaces["org.iso.18013.5.1"]
    assertEquals(mapOf("family_name" to true, "portrait" to false, "age_over_18" to false), mdlElements)
    assertEquals(vectors.getString("sessionTranscriptBytes"), request.sessionTranscript.toHex())
    assertEquals(1, request.requestNumber)
  }

  @Test
  fun `present sends the wallet's response and completes when the reader ends`() = runTest {
    transport.queue(message("sessionEstablishment"))
    transport.queue(ReaderEvent.Ended)

    val result = presenter.present(options, RecordingCallbacks())

    assertEquals(messages.getString("responseSessionData"), transport.sent.single().toHex())
    assertEquals(true, (result as MdocResult.Completed).confirmedByReader)
  }

  @Test
  fun `present asks the holder again for a further request`() = runTest {
    val callbacks = RecordingCallbacks()
    transport.queue(message("sessionEstablishment"))
    transport.queue(message("secondRequestSessionData"))
    transport.queue(ReaderEvent.Ended)

    presenter.present(options, callbacks)

    assertEquals(listOf(1, 2), callbacks.requests.map { it.requestNumber })
    assertEquals(2, transport.sent.size)
  }

  @Test
  fun `present sends status 20 and returns Declined when the holder declines`() = runTest {
    transport.queue(message("sessionEstablishment"))

    val result = presenter.present(options, RecordingCallbacks { MdocSharingDecision.Decline })

    assertEquals(MdocResult.Declined, result)
    assertEquals(messages.getString("statusTermination"), transport.sent.single().toHex())
  }

  @Test
  fun `present reports the reader's trust for each document`() = runTest {
    val callbacks = RecordingCallbacks()
    transport.queue(ReaderEvent.Message(auth.getString("signedSessionEstablishment").hexToBytes()))
    transport.queue(ReaderEvent.Ended)
    val withRoots = options.copy(trustedReaderRoots = listOf(auth.getString("rootCertificate").hexToBytes()))

    presenter.present(withRoots, callbacks)

    val trust = callbacks.requests.single().documents.single().readerTrust
    assertEquals("Tuvali Test Reader", (trust as ReaderTrustStatus.Verified).readerName)
  }

  @Test
  fun `present reports progress from waiting for a reader to sending the response`() = runTest {
    val callbacks = RecordingCallbacks()
    transport.queue(message("sessionEstablishment"))
    transport.queue(ReaderEvent.Ended)

    presenter.present(options, callbacks)

    assertEquals(
      listOf(
        MdocProgress.WAITING_FOR_READER,
        MdocProgress.CONNECTED,
        MdocProgress.REQUEST_RECEIVED,
        MdocProgress.SENDING_RESPONSE,
      ),
      callbacks.progress,
    )
  }

  // ---------- timeouts ----------

  @Test
  fun `present returns NO_READER_FOUND when no reader connects within the engagement timeout`() = runTest {
    transport.waitForReader = true
    val presenting = startPresenting(RecordingCallbacks())

    advanceTimeBy(60.seconds.inWholeMilliseconds + 1)

    assertEquals(MdocFailure.NO_READER_FOUND, (presenting.await() as MdocResult.Failed).failure)
  }

  @Test
  fun `present returns TIMED_OUT and sends status 20 when the holder does not decide within the inactivity timeout`() = runTest {
    transport.queue(message("sessionEstablishment"))
    val presenting = startPresenting(RecordingCallbacks { CompletableDeferred<MdocSharingDecision>().await() })

    advanceTimeBy(300.seconds.inWholeMilliseconds + 1)

    assertEquals(MdocFailure.TIMED_OUT, (presenting.await() as MdocResult.Failed).failure)
    assertEquals(messages.getString("statusTermination"), transport.sent.single().toHex())
  }

  @Test
  fun `present returns TIMED_OUT when the reader sends nothing within the inactivity timeout`() = runTest {
    val presenting = startPresenting(RecordingCallbacks())

    advanceTimeBy(300.seconds.inWholeMilliseconds + 1)

    assertEquals(MdocFailure.TIMED_OUT, (presenting.await() as MdocResult.Failed).failure)
  }

  // ---------- cancel ----------

  @Test
  fun `cancel while waiting for a reader returns Cancelled`() = runTest {
    transport.waitForReader = true
    val presenting = startPresenting(RecordingCallbacks())

    presenter.cancel()

    assertEquals(MdocResult.Cancelled, presenting.await())
  }

  @Test
  fun `cancel while the holder decides sends status 20 and returns Cancelled`() = runTest {
    transport.queue(message("sessionEstablishment"))
    val presenting = startPresenting(RecordingCallbacks { CompletableDeferred<MdocSharingDecision>().await() })

    presenter.cancel()

    assertEquals(MdocResult.Cancelled, presenting.await())
    assertEquals(messages.getString("statusTermination"), transport.sent.single().toHex())
  }

  // ---------- the connection and the reader failing ----------

  @Test
  fun `present returns BLUETOOTH_OFF when Bluetooth is switched off while looking for a reader`() = runTest {
    transport.failOnOpen = GattDisconnectedException(LinkLossReason.BLUETOOTH_TURNED_OFF)

    val result = presenter.present(options, RecordingCallbacks())

    assertEquals(MdocFailure.BLUETOOTH_OFF, (result as MdocResult.Failed).failure)
  }

  @Test
  fun `present returns CONNECTION_LOST when the link drops before the request`() = runTest {
    transport.dropLink(GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED))

    val result = presenter.present(options, RecordingCallbacks())

    assertEquals(MdocFailure.CONNECTION_LOST, (result as MdocResult.Failed).failure)
  }

  @Test
  fun `present completes without the reader's confirmation when the link drops right after the answer`() = runTest {
    transport.queue(message("sessionEstablishment"))
    transport.dropLink(GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED))

    val result = presenter.present(options, RecordingCallbacks())

    assertEquals(false, (result as MdocResult.Completed).confirmedByReader)
  }

  @Test
  fun `present returns READER_SECURITY_ERROR for a request that does not decrypt, without a second status`() = runTest {
    val tampered = messages.getString("sessionEstablishment").hexToBytes()
      .also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    transport.queue(ReaderEvent.Message(tampered))

    val result = presenter.present(options, RecordingCallbacks())

    assertEquals(MdocFailure.READER_SECURITY_ERROR, (result as MdocResult.Failed).failure)
    // only the session's status 10, no status 20 on top
    assertEquals(listOf(messages.getString("statusEncryptionError")), transport.sent.map { it.toHex() })
  }

  @Test
  fun `present returns REQUEST_UNREADABLE for a message that is not CBOR, without a second status`() = runTest {
    transport.queue(ReaderEvent.Message("a2".hexToBytes()))

    val result = presenter.present(options, RecordingCallbacks())

    assertEquals(MdocFailure.REQUEST_UNREADABLE, (result as MdocResult.Failed).failure)
    assertEquals(listOf(messages.getString("statusCborError")), transport.sent.map { it.toHex() })
  }

  @Test
  fun `present returns READER_ENDED when the reader ends before sending a request`() = runTest {
    transport.queue(ReaderEvent.Ended)

    val result = presenter.present(options, RecordingCallbacks())

    assertEquals(MdocFailure.READER_ENDED, (result as MdocResult.Failed).failure)
    assertTrue(transport.sent.isEmpty())
  }

  @Test
  fun `present returns INTERNAL_ERROR and sends status 20 when the wallet's onRequest fails`() = runTest {
    transport.queue(message("sessionEstablishment"))

    val result = presenter.present(options, RecordingCallbacks { throw IllegalStateException("signing failed") })

    assertEquals(MdocFailure.INTERNAL_ERROR, (result as MdocResult.Failed).failure)
    assertEquals(messages.getString("statusTermination"), transport.sent.single().toHex())
  }

  // ---------- cleanup and misuse ----------

  @Test
  fun `present always closes the transport and wipes the device key`() = runTest {
    transport.queue(message("sessionEstablishment"))
    transport.queue(ReaderEvent.Ended)

    presenter.present(options, RecordingCallbacks())

    assertTrue(transport.closed)
    assertThrows(IllegalStateException::class.java) { deviceKey.requirePrivateKey() }
  }

  @Test
  fun `present throws IllegalStateException while another presentation is running`() = runTest {
    transport.waitForReader = true
    val first = startPresenting(RecordingCallbacks())

    val failure = runCatching { presenter.present(options, RecordingCallbacks()) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    presenter.cancel()
    first.await()
  }

  @Test
  fun `present throws IllegalArgumentException when a trusted reader root is not a certificate`() = runTest {
    val broken = options.copy(trustedReaderRoots = listOf(byteArrayOf(1, 2, 3)))

    val failure = runCatching { presenter.present(broken, RecordingCallbacks()) }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
  }
}
