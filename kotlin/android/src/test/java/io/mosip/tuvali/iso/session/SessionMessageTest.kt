package io.mosip.tuvali.iso.session

import io.mosip.tuvali.iso.cbor.cborMapOf
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.tag24
import io.mosip.tuvali.iso.cbor.untag24
import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The session messages, SessionEstablishment and SessionData: ISO 18013-5 clause 9.1.1.4. */
class SessionMessageTest {

  private val vectors = SessionTestVectors.session
  private val session = vectors.getJSONObject("session")

  @Test
  fun `decode reads a SessionEstablishment with the reader key bytes as sent and the encrypted request`() {
    val bytes = session.getString("sessionEstablishment").hexToBytes()
    val eReaderKeyBytes = vectors.getJSONObject("readerKey").getString("eReaderKeyBytes").hexToBytes()
    val vectorReaderKey = untag24(decodeCbor(eReaderKeyBytes))

    val establishment = SessionMessage.decode(bytes) as SessionMessage.Establishment

    assertEquals(vectorReaderKey.toHex(), establishment.readerCoseKey.toHex())
    assertEquals(session.getString("twoDocRequest").length / 2 + 16, establishment.data.size)
  }

  @Test
  fun `decode reads a message without eReaderKey as SessionData`() {
    // only SessionEstablishment carries eReaderKey
    val withoutKey = encodeCbor(cborMapOf("data" to byteArrayOf(1)))

    val message = SessionMessage.decode(withoutKey)

    assertEquals("01", (message as SessionMessage.Data).data?.toHex())
  }

  @Test
  fun `decode throws CborDecodingException when eReaderKey is not tag 24`() {
    // the key as a plain byte string, without the tag 24 wrapper
    val untagged = encodeCbor(cborMapOf("eReaderKey" to byteArrayOf(1), "data" to byteArrayOf(1)))

    assertThrows(CborDecodingException::class.java) { SessionMessage.decode(untagged) }
  }

  @Test
  fun `decode throws CborDecodingException when a SessionEstablishment has no data`() {
    val withoutData = encodeCbor(cborMapOf("eReaderKey" to tag24(byteArrayOf(0xa0.toByte()))))

    assertThrows(CborDecodingException::class.java) { SessionMessage.decode(withoutData) }
  }

  @Test
  fun `decode throws CborDecodingException when the message is not a map`() {
    val notAMap = "80".hexToBytes()

    assertThrows(CborDecodingException::class.java) { SessionMessage.decode(notAMap) }
  }

  @Test
  fun `Data encode writes only the data when there is no status`() {
    val data = "a1b2".hexToBytes()

    val encoded = SessionMessage.Data(data = data).encode()

    assertEquals("a1646461746142a1b2", encoded.toHex())
  }

  @Test
  fun `Data encode writes only the status for a session termination`() {
    val encoded = SessionMessage.Data(status = SessionStatus.SESSION_TERMINATION).encode()

    assertEquals(session.getString("statusTermination"), encoded.toHex())
  }

  @Test
  fun `Data throws IllegalArgumentException when status 10 or 11 comes with data`() {
    // Table 20: with status 10 or 11 the data element shall not be present
    assertThrows(IllegalArgumentException::class.java) {
      SessionMessage.Data(data = byteArrayOf(1), status = SessionStatus.ENCRYPTION_ERROR)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SessionMessage.Data(data = byteArrayOf(1), status = SessionStatus.CBOR_DECODING_ERROR)
    }
  }

  @Test
  fun `decode reads a reader's status message as SessionData`() {
    val readerEnds = session.getString("statusTermination").hexToBytes()

    val message = SessionMessage.decode(readerEnds) as SessionMessage.Data

    assertEquals(20, message.status)
    assertNull(message.data)
  }

  @Test
  fun `decode ignores keys it does not know`() {
    // a later version might add keys; this one carries an extra "x"
    val withExtra = encodeCbor(cborMapOf("data" to byteArrayOf(7), "x" to 1L))

    val message = SessionMessage.decode(withExtra) as SessionMessage.Data

    assertEquals("07", message.data?.toHex())
  }

  @Test
  fun `decode throws CborDecodingException when data is not a byte string`() {
    val wrongType = encodeCbor(cborMapOf("data" to "text"))

    assertThrows(CborDecodingException::class.java) { SessionMessage.decode(wrongType) }
  }

  @Test
  fun `decode throws CborDecodingException when status is not an unsigned integer`() {
    val wrongType = encodeCbor(cborMapOf("status" to "20"))

    assertThrows(CborDecodingException::class.java) { SessionMessage.decode(wrongType) }
  }
}
