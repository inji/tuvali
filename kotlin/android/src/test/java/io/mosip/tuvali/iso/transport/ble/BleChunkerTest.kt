package io.mosip.tuvali.iso.transport.ble

import io.mosip.tuvali.iso.exception.IsoTransportException
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** BLE parts of MTU − 3 bytes, each starting 0x01 (more) or 0x00 (last): ISO 18013-5 clause 8.3.3.1.1.6. */
class BleChunkerTest {

  // the smallest ATT MTU: parts of 20 bytes, so 19 bytes of message per part
  private val smallestMtu = 23

  @Test
  fun `split sends a short message as one last part`() {
    val message = "a1b2".hexToBytes()

    val parts = BleChunker(smallestMtu).split(message)

    assertEquals(listOf("00a1b2"), parts.map { it.toHex() })
  }

  @Test
  fun `split fits a message of exactly one part payload into one part`() {
    val message = ByteArray(19) { 7 }

    val parts = BleChunker(smallestMtu).split(message)

    assertEquals(1, parts.size)
    assertEquals(20, parts[0].size)
  }

  @Test
  fun `split starts a second part when the message is one byte over`() {
    val message = ByteArray(20) { it.toByte() }

    val parts = BleChunker(smallestMtu).split(message)

    assertEquals(listOf(20, 2), parts.map { it.size })
    assertEquals(listOf(0x01.toByte(), 0x00.toByte()), parts.map { it[0] })
  }

  @Test
  fun `split never makes a part longer than MTU minus 3 bytes`() {
    // the largest MTU the client uses: parts of 512 bytes, the largest attribute value
    val mtu = 515
    val message = ByteArray(2000) { it.toByte() }

    val parts = BleChunker(mtu).split(message)

    assertEquals(512, parts.maxOf { it.size })
  }

  @Test
  fun `split sends an empty message as a single last part with no payload`() {
    val parts = BleChunker(smallestMtu).split(ByteArray(0))

    assertEquals(listOf("00"), parts.map { it.toHex() })
  }

  @Test
  fun `BleChunker throws IllegalArgumentException when the MTU leaves no room for message bytes`() {
    // MTU 4 gives parts of 1 byte, which only holds the header
    assertThrows(IllegalArgumentException::class.java) { BleChunker(4) }
  }

  @Test
  fun `addPiece returns nothing until the last part, then the whole message`() {
    val chunker = BleChunker(smallestMtu)

    val afterFirst = chunker.addPiece("01a1b2".hexToBytes())
    val afterLast = chunker.addPiece("00c3".hexToBytes())

    assertNull(afterFirst)
    assertEquals("a1b2c3", afterLast?.toHex())
  }

  @Test
  fun `addPiece starts a fresh message after completing one`() {
    val chunker = BleChunker(smallestMtu)
    chunker.addPiece("00a1".hexToBytes())

    val second = chunker.addPiece("00b2".hexToBytes())

    assertEquals("b2", second?.toHex())
  }

  @Test
  fun `addPiece rebuilds any message that split produced`() {
    val mtus = listOf(23, 24, 185, 515)
    val sizes = listOf(0, 1, 18, 19, 20, 38, 39, 511, 512, 513, 5000)

    for (mtu in mtus) for (size in sizes) {
      val message = ByteArray(size) { (it * 31 + size).toByte() }
      val chunker = BleChunker(mtu)

      val rebuilt = chunker.split(message).map { chunker.addPiece(it) }.last()

      assertArrayEquals("mtu $mtu, size $size", message, rebuilt)
    }
  }

  @Test
  fun `addPiece throws IsoTransportException for an empty part`() {
    val chunker = BleChunker(smallestMtu)

    assertThrows(IsoTransportException::class.java) { chunker.addPiece(ByteArray(0)) }
  }

  @Test
  fun `addPiece throws IsoTransportException when the header is neither 0x01 nor 0x00`() {
    val chunker = BleChunker(smallestMtu)

    assertThrows(IsoTransportException::class.java) { chunker.addPiece("02a1".hexToBytes()) }
  }

  @Test
  fun `addPiece throws IsoTransportException when a message grows past the size limit`() {
    // a 4-byte limit, so the second part pushes the message to 6 bytes
    val chunker = BleChunker(smallestMtu, maxMessageBytes = 4)
    chunker.addPiece("01a1a2a3".hexToBytes())

    assertThrows(IsoTransportException::class.java) { chunker.addPiece("01b1b2b3".hexToBytes()) }
  }
}
