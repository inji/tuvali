package io.mosip.tuvali.iso.transport.ble

import io.mosip.tuvali.iso.exception.IsoTransportException
import io.mosip.tuvali.iso.transport.IsoChunker
import java.io.ByteArrayOutputStream

// ATT write and notification headers take 3 bytes of the MTU
private const val ATT_HEADER_BYTES = 3

// first byte of every part (clause 8.3.3.1.1.6)
private const val PART_MORE_FOLLOWS: Byte = 0x01
private const val PART_LAST: Byte = 0x00

/** Large enough for any request or response with certificates; stops a reader exhausting memory. */
private const val DEFAULT_MAX_MESSAGE_BYTES = 1024 * 1024

/**
 * BLE parts (clause 8.3.3.1.1.6): each part is at most MTU − 3 bytes and starts with 0x01 if more
 * parts follow or 0x00 for the last one, so it carries MTU − 4 bytes of the message.
 */
internal class BleChunker(
  mtu: Int,
  private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
) : IsoChunker {
  private val payloadBytes = mtu - ATT_HEADER_BYTES - 1
  private val messageSoFar = ByteArrayOutputStream()

  init {
    require(payloadBytes > 0) { "MTU $mtu leaves no room for message bytes" }
  }

  override fun split(message: ByteArray): List<ByteArray> {
    if (message.isEmpty()) return listOf(byteArrayOf(PART_LAST))

    return (message.indices step payloadBytes).map { start ->
      val end = minOf(start + payloadBytes, message.size)
      val header = if (end < message.size) PART_MORE_FOLLOWS else PART_LAST
      byteArrayOf(header) + message.copyOfRange(start, end)
    }
  }

  override fun addPiece(piece: ByteArray): ByteArray? {
    if (piece.isEmpty()) throw IsoTransportException("Empty message part")
    val header = piece[0]
    if (header != PART_MORE_FOLLOWS && header != PART_LAST) {
      throw IsoTransportException("Message part header must be 0x01 or 0x00, was 0x%02x".format(header))
    }
    if (messageSoFar.size() + piece.size - 1 > maxMessageBytes) {
      throw IsoTransportException("Message is larger than $maxMessageBytes bytes")
    }
    messageSoFar.write(piece, 1, piece.size - 1)
    if (header == PART_MORE_FOLLOWS) return null

    return messageSoFar.toByteArray().also { messageSoFar.reset() }
  }
}
