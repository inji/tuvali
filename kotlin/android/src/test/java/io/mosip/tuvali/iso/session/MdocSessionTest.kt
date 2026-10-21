package io.mosip.tuvali.iso.session

import io.mosip.tuvali.ble.central.coroutine.LinkLossReason
import io.mosip.tuvali.iso.exception.GattDisconnectedException
import io.mosip.tuvali.iso.cbor.cborMapOf
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.requireStrictCbor
import io.mosip.tuvali.iso.cbor.tag24
import io.mosip.tuvali.iso.cbor.untag24
import io.mosip.tuvali.iso.crypto.EphemeralDeviceKey
import io.mosip.tuvali.iso.engagement.DeviceEngagement
import io.mosip.tuvali.iso.engagement.RetrievalMethod
import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.iso.exception.InvalidReaderKeyException
import io.mosip.tuvali.iso.exception.SessionDecryptionException
import io.mosip.tuvali.iso.exception.SessionProtocolException
import io.mosip.tuvali.iso.transport.ReaderEvent
import io.mosip.tuvali.testutils.ScriptedTransport
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.encodeCanonicalCbor
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** One mdoc session over a transport: ISO 18013-5 clause 9.1.1.4 steps 2 to 4. */
class MdocSessionTest {

  private val vectors = SessionTestVectors.session
  private val messages = vectors.getJSONObject("session")

  private val deviceKey = EphemeralDeviceKey.fromPrivateScalar(
    vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes()
  )
  private val engagement = DeviceEngagement.create(
    deviceKey,
    listOf(RetrievalMethod.BleCentralClient(UUID.fromString(vectors.getString("bleClientUuid")))),
  )

  private fun message(name: String) = ReaderEvent.Message(messages.getString(name).hexToBytes())

  private fun sessionWith(vararg incoming: ReaderEvent): Pair<MdocSession, ScriptedTransport> {
    val transport = ScriptedTransport(*incoming)
    return MdocSession(transport, engagement) to transport
  }

  // ---------- the normal exchange ----------

  @Test
  fun `receiveRequest sets up the session and returns the reader's decrypted request`() = runTest {
    val (session, _) = sessionWith(message("sessionEstablishment"))

    val event = session.receiveRequest()

    val deviceRequest = (event as SessionEvent.Request).deviceRequest
    assertEquals(listOf("org.iso.18013.5.1.mDL", "org.example.test.1"), deviceRequest.docRequests.map { it.docType })
  }

  @Test
  fun `respond sends the encrypted response in a SessionData message`() = runTest {
    val (session, transport) = sessionWith(message("sessionEstablishment"))
    session.receiveRequest()

    session.respond(messages.getString("deviceResponse").hexToBytes())

    assertEquals(messages.getString("responseSessionData"), transport.sent.single().toHex())
  }

  @Test
  fun `terminate sends status 20`() = runTest {
    val (session, transport) = sessionWith(message("sessionEstablishment"))
    session.receiveRequest()

    session.terminate()

    assertEquals(messages.getString("statusTermination"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest decrypts a further request with the next counter`() = runTest {
    val (session, _) = sessionWith(message("sessionEstablishment"), message("secondRequestSessionData"))
    session.receiveRequest()

    val event = session.receiveRequest()

    assertEquals(1, (event as SessionEvent.Request).deviceRequest.docRequests.size)
  }

  @Test
  fun `everything the session sends is strictly encoded CBOR with canonical key order`() = runTest {
    val (session, transport) = sessionWith(message("sessionEstablishment"))
    session.receiveRequest()

    session.respond(messages.getString("deviceResponse").hexToBytes())
    session.terminate()

    transport.sent.forEach { sent ->
      requireStrictCbor(sent)
      assertEquals(encodeCanonicalCbor(decodeCbor(sent)).toHex(), sent.toHex())
    }
  }

  // ---------- the reader ending ----------

  @Test
  fun `receiveRequest returns Ended when the reader signals End on the transport`() = runTest {
    val (session, _) = sessionWith(ReaderEvent.Ended)

    val event = session.receiveRequest()

    assertEquals(null, (event as SessionEvent.Ended).status)
  }

  @Test
  fun `receiveRequest returns Ended with the status when the reader sends status 20`() = runTest {
    val (session, transport) = sessionWith(message("sessionEstablishment"), message("statusTermination"))
    session.receiveRequest()

    val event = session.receiveRequest()

    assertEquals(20, (event as SessionEvent.Ended).status)
    assertTrue(transport.sent.isEmpty())
  }

  // ---------- errors and the status the reader is told ----------

  @Test
  fun `receiveRequest sends status 10 and throws InvalidReaderKeyException for an invalid reader key`() = runTest {
    // a SessionEstablishment whose reader key is not a point on P-256
    val badKey = encodeCbor(cborMapOf(1L to 2L, -1L to 1L, -2L to ByteArray(32), -3L to ByteArray(32)))
    val establishment = encodeCbor(cborMapOf("eReaderKey" to tag24(badKey), "data" to ByteArray(20)))
    val (session, transport) = sessionWith(ReaderEvent.Message(establishment))

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is InvalidReaderKeyException)
    assertEquals(messages.getString("statusEncryptionError"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest sends status 11 and throws CborDecodingException for an unreadable first message`() = runTest {
    // not CBOR at all: a truncated map header
    val (session, transport) = sessionWith(ReaderEvent.Message("a2".hexToBytes()))

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is CborDecodingException)
    assertEquals(messages.getString("statusCborError"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest sends status 10 and throws SessionDecryptionException when the request does not decrypt`() = runTest {
    val tampered = messages.getString("sessionEstablishment").hexToBytes()
      .also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    val (session, transport) = sessionWith(ReaderEvent.Message(tampered))

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is SessionDecryptionException)
    assertEquals(messages.getString("statusEncryptionError"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest sends status 11 and throws CborDecodingException when the decrypted request is malformed`() = runTest {
    // a request that decrypts correctly but has no docRequests
    val malformedRequest = encodeCbor(cborMapOf("version" to "1.0"))
    val eReaderKeyBytes = vectors.getJSONObject("readerKey").getString("eReaderKeyBytes").hexToBytes()
    val readerCoseKey = untag24(decodeCbor(eReaderKeyBytes))
    val establishment = encodeCbor(
      cborMapOf("eReaderKey" to tag24(readerCoseKey), "data" to encryptAsReader(malformedRequest))
    )
    val (session, transport) = sessionWith(ReaderEvent.Message(establishment))

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is CborDecodingException)
    assertEquals(messages.getString("statusCborError"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest sends status 10 and throws SessionProtocolException for a second SessionEstablishment`() = runTest {
    // the reader tries to establish the session again: it is never re-keyed
    val (session, transport) = sessionWith(message("sessionEstablishment"), message("sessionEstablishment"))
    session.receiveRequest()

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is SessionProtocolException)
    assertEquals(messages.getString("statusEncryptionError"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest sends status 11 and throws SessionProtocolException when the first message is SessionData`() = runTest {
    // a request before any SessionEstablishment, so there are no session keys yet
    val (session, transport) = sessionWith(message("secondRequestSessionData"))

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is SessionProtocolException)
    assertEquals(messages.getString("statusCborError"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest sends status 11 for a SessionData with neither data nor status`() = runTest {
    val empty = encodeCbor(cborMapOf("x" to 1L))
    val (session, transport) = sessionWith(message("sessionEstablishment"), ReaderEvent.Message(empty))
    session.receiveRequest()

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is SessionProtocolException)
    assertEquals(messages.getString("statusCborError"), transport.sent.single().toHex())
  }

  @Test
  fun `receiveRequest still throws the original error when telling the reader fails`() = runTest {
    val (session, transport) = sessionWith(ReaderEvent.Message("a2".hexToBytes()))
    transport.failOnSend = GattDisconnectedException(LinkLossReason.REMOTE_DISCONNECTED)

    val failure = runCatching { session.receiveRequest() }.exceptionOrNull()

    assertTrue(failure is CborDecodingException)
  }

  // ---------- order and cleanup ----------

  @Test
  fun `respond throws IllegalStateException before a request was received`() = runTest {
    val (session, _) = sessionWith()

    val failure = runCatching { session.respond(byteArrayOf(1)) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
  }

  @Test
  fun `wipe destroys the session keys and the device key`() = runTest {
    val (session, _) = sessionWith(message("sessionEstablishment"))
    session.receiveRequest()

    session.wipe()

    assertThrows(IllegalStateException::class.java) { deviceKey.requirePrivateKey() }
    assertTrue(runCatching { session.respond(byteArrayOf(1)) }.exceptionOrNull() is IllegalStateException)
  }

  /** Encrypts [plaintext] as the reader's first message would be: SKReader, reader identifier, counter 1. */
  private fun encryptAsReader(plaintext: ByteArray): ByteArray {
    val iv = "000000000000000000000001".hexToBytes()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
      val skReader = SecretKeySpec(vectors.getString("skReader").hexToBytes(), "AES")
      init(Cipher.ENCRYPT_MODE, skReader, GCMParameterSpec(128, iv))
    }
    return cipher.doFinal(plaintext)
  }
}
