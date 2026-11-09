package io.mosip.tuvali.iso.transport

/** What the reader sent: a whole message, or a signal that it ended the session. */
internal sealed class ReaderEvent {
  class Message(val bytes: ByteArray) : ReaderEvent()
  object Ended : ReaderEvent()
}

/**
 * Carries whole ISO 18013-5 session messages (SessionEstablishment, SessionData) between the
 * mdoc and the reader. Each data retrieval method (BLE today; NFC, L2CAP or Wi-Fi Aware later)
 * is one implementation; the session layer above only ever sees whole messages.
 */
internal interface MdocTransport {
  /** Finds and connects to the reader, ready to exchange messages. */
  suspend fun open()

  suspend fun send(message: ByteArray)

  /** Waits for the reader's next whole message, or for it to end the session. */
  suspend fun receive(): ReaderEvent

  /** Ends the connection; safe to call more than once and after a lost link. */
  suspend fun close()
}
