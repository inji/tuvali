package io.mosip.tuvali.iso.transport

/**
 * How a transport cuts a message into the pieces its medium can carry, and joins received pieces
 * back into whole messages. One instance serves one connection, as [addPiece] keeps the partial
 * message received so far.
 *
 * The contract is shaped by BLE (a list of pieces sent in order). A future NFC chunker, whose
 * pieces follow commands and responses, may need it adjusted; it is internal, so that is cheap.
 */
internal interface IsoChunker {
  /** The pieces to send for [message], in order. */
  fun split(message: ByteArray): List<ByteArray>

  /** Adds one received piece; returns the whole message once it is complete, otherwise null. */
  fun addPiece(piece: ByteArray): ByteArray?
}
