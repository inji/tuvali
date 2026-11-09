package io.mosip.tuvali.iso.transport

/**
 * Splits outgoing messages and reassembles incoming pieces. One instance per connection;
 * addPiece retains the current partial message.
 */
internal interface IsoChunker {
  fun split(message: ByteArray): List<ByteArray>

  /** Adds one received piece; returns the whole message once it is complete, otherwise null. */
  fun addPiece(piece: ByteArray): ByteArray?
}
