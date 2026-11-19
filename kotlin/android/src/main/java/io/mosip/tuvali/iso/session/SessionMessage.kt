package io.mosip.tuvali.iso.session

import io.mosip.tuvali.iso.cbor.asBytes
import io.mosip.tuvali.iso.cbor.asMap
import io.mosip.tuvali.iso.cbor.asSmallUnsignedInt
import io.mosip.tuvali.iso.cbor.cborMapOf
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.optional
import io.mosip.tuvali.iso.cbor.required
import io.mosip.tuvali.iso.cbor.untag24
import io.mosip.tuvali.iso.exception.CborDecodingException

private const val KEY_READER_KEY = "eReaderKey"
private const val KEY_DATA = "data"
private const val KEY_STATUS = "status"

/** The two message kinds of an ISO 18013-5 session (clause 9.1.1.4). */
internal sealed class SessionMessage {

  /**
   * SessionEstablishment: the reader's first message, with its ephemeral key and the encrypted
   * request. [readerCoseKey] are the COSE_Key bytes from inside EReaderKeyBytes, exactly as received.
   */
  class Establishment(val readerCoseKey: ByteArray, val data: ByteArray) : SessionMessage()

  /**
   * SessionData: every later message, in both directions: encrypted [data], a [status], or both.
   * With status 10 or 11 there is no data (Table 20).
   */
  class Data(val data: ByteArray? = null, val status: Int? = null) : SessionMessage() {
    init {
      require(!carriesDataWithErrorStatus(data, status)) { "SessionData with status $status must not carry data" }
    }

    fun encode(): ByteArray {
      // "data" before "status" also keeps the map keys in canonical order
      val entries = buildList<Pair<Any, Any?>> {
        data?.let { add(KEY_DATA to it) }
        status?.let { add(KEY_STATUS to it.toLong()) }
      }
      return encodeCbor(cborMapOf(*entries.toTypedArray()))
    }
  }

  companion object {
    private fun carriesDataWithErrorStatus(data: ByteArray?, status: Int?): Boolean =
      data != null && (status == SessionStatus.ENCRYPTION_ERROR || status == SessionStatus.CBOR_DECODING_ERROR)

    /**
     * Decodes a message from the reader. Only SessionEstablishment carries "eReaderKey", so that
     * key decides the kind; unknown keys are ignored. Malformed input is a CborDecodingException.
     */
    fun decode(message: ByteArray): SessionMessage {
      val map = decodeCbor(message).asMap("Session message")
      val readerKey = map.optional(KEY_READER_KEY)
      if (readerKey == null) {
        val data = map.optional(KEY_DATA)?.asBytes(KEY_DATA)
        val status = map.optional(KEY_STATUS)?.asSmallUnsignedInt(KEY_STATUS)
        if (carriesDataWithErrorStatus(data, status)) {
          throw CborDecodingException("SessionData with status $status must not carry data")
        }
        return Data(data, status)
      }
      return Establishment(
        readerCoseKey = untag24(readerKey),
        data = map.required(KEY_DATA, "SessionEstablishment").asBytes(KEY_DATA),
      )
    }
  }
}
