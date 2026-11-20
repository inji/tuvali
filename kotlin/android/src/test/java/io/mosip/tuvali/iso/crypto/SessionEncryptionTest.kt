package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.untag24
import io.mosip.tuvali.iso.exception.SessionDecryptionException
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** AES-256-GCM with identifier || counter IVs: ISO 18013-5 clause 9.1.1.5. */
class SessionEncryptionTest {

  private val vectors = SessionTestVectors.session
  private val firstFromReader = vectors.getJSONObject("readerToMdoc")
  private val secondFromReader = vectors.getJSONObject("readerToMdocSecond")
  private val firstToReader = vectors.getJSONObject("mdocToReader")
  private val secondToReader = vectors.getJSONObject("mdocToReaderSecond")

  private fun vectorKeys() = SessionKeys(vectors.getString("skReader").hexToBytes(), vectors.getString("skDevice").hexToBytes())

  @Test
  fun `decryptFromReader decrypts the first reader message with counter 1`() {
    val encryption = SessionEncryption(vectorKeys())

    val plaintext = encryption.decryptFromReader(firstFromReader.getString("ciphertext").hexToBytes())

    assertEquals(firstFromReader.getString("plaintext"), plaintext.toHex())
  }

  @Test
  fun `decryptFromReader decrypts the next reader message with counter 2`() {
    val encryption = SessionEncryption(vectorKeys())
    encryption.decryptFromReader(firstFromReader.getString("ciphertext").hexToBytes())

    val plaintext = encryption.decryptFromReader(secondFromReader.getString("ciphertext").hexToBytes())

    assertEquals(secondFromReader.getString("plaintext"), plaintext.toHex())
  }

  @Test
  fun `encryptForReader reproduces the first mdoc message with counter 1`() {
    val encryption = SessionEncryption(vectorKeys())

    val ciphertext = encryption.encryptForReader(firstToReader.getString("plaintext").hexToBytes())

    assertEquals(firstToReader.getString("ciphertext"), ciphertext.toHex())
  }

  @Test
  fun `encryptForReader uses counter 2 for the next mdoc message`() {
    val encryption = SessionEncryption(vectorKeys())
    encryption.encryptForReader(firstToReader.getString("plaintext").hexToBytes())

    val ciphertext = encryption.encryptForReader(secondToReader.getString("plaintext").hexToBytes())

    assertEquals(secondToReader.getString("ciphertext"), ciphertext.toHex())
  }

  @Test
  fun `decryptFromReader throws SessionDecryptionException when the ciphertext was changed`() {
    val encryption = SessionEncryption(vectorKeys())
    val tampered = firstFromReader.getString("ciphertext").hexToBytes().also { it[0] = (it[0] + 1).toByte() }

    assertThrows(SessionDecryptionException::class.java) { encryption.decryptFromReader(tampered) }
  }

  @Test
  fun `decryptFromReader throws SessionDecryptionException when the authentication tag was changed`() {
    val encryption = SessionEncryption(vectorKeys())
    // the last 16 bytes are the GCM tag
    val tampered = firstFromReader.getString("ciphertext").hexToBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

    assertThrows(SessionDecryptionException::class.java) { encryption.decryptFromReader(tampered) }
  }

  @Test
  fun `decryptFromReader throws SessionDecryptionException for a message encrypted with the device key`() {
    val encryption = SessionEncryption(vectorKeys())
    // a message in the mdoc-to-reader direction, encrypted with SKDevice and the mdoc identifier
    val wrongDirection = firstToReader.getString("ciphertext").hexToBytes()

    assertThrows(SessionDecryptionException::class.java) { encryption.decryptFromReader(wrongDirection) }
  }

  @Test
  fun `decryptFromReader throws SessionDecryptionException when the reader skips a counter`() {
    val encryption = SessionEncryption(vectorKeys())
    // the counter 2 message arriving first, while counter 1 is expected
    val outOfOrder = secondFromReader.getString("ciphertext").hexToBytes()

    assertThrows(SessionDecryptionException::class.java) { encryption.decryptFromReader(outOfOrder) }
  }

  @Test
  fun `decryptFromReader throws SessionDecryptionException when the data is shorter than the tag`() {
    val encryption = SessionEncryption(vectorKeys())
    // GCM data is ciphertext followed by a 16-byte tag, so 5 bytes cannot be valid
    val tooShort = ByteArray(5)

    assertThrows(SessionDecryptionException::class.java) { encryption.decryptFromReader(tooShort) }
  }

  @Test
  fun `decryptFromReader does not move the counter on when decryption fails`() {
    val encryption = SessionEncryption(vectorKeys())
    val tampered = firstFromReader.getString("ciphertext").hexToBytes().also { it[0] = (it[0] + 1).toByte() }
    runCatching { encryption.decryptFromReader(tampered) }

    val plaintext = encryption.decryptFromReader(firstFromReader.getString("ciphertext").hexToBytes())

    assertEquals(firstFromReader.getString("plaintext"), plaintext.toHex())
  }

  @Test
  fun `encryptForReader writes the largest counter as four unsigned big-endian bytes`() {
    // the next message uses counter 0xFFFFFFFF, the last one allowed
    val encryption = SessionEncryption(vectorKeys(), lastCounterToReader = 0xFFFF_FFFEL)
    val plaintext = "a0".hexToBytes()

    val ciphertext = encryption.encryptForReader(plaintext)

    // decrypt independently with the JDK, using the mdoc identifier and counter ff ff ff ff
    val iv = "0000000000000001ffffffff".hexToBytes()
    val jdkCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(Cipher.DECRYPT_MODE, SecretKeySpec(vectors.getString("skDevice").hexToBytes(), "AES"), GCMParameterSpec(128, iv))
    }
    assertEquals(plaintext.toHex(), jdkCipher.doFinal(ciphertext).toHex())
  }

  @Test
  fun `encryptForReader throws IllegalStateException when the message counter is used up`() {
    // the last message already used the largest 4-byte counter
    val encryption = SessionEncryption(vectorKeys(), lastCounterToReader = 0xFFFF_FFFFL)

    assertThrows(IllegalStateException::class.java) { encryption.encryptForReader(byteArrayOf(1)) }
  }

  @Test
  fun `decryptFromReader throws IllegalStateException when the message counter is used up`() {
    // the last reader message already used the largest 4-byte counter
    val encryption = SessionEncryption(vectorKeys(), lastCounterFromReader = 0xFFFF_FFFFL)

    assertThrows(IllegalStateException::class.java) { encryption.decryptFromReader(byteArrayOf(1)) }
  }

  @Test
  fun `encryptForReader and decryptFromReader throw IllegalStateException after wipe`() {
    val encryption = SessionEncryption(vectorKeys())

    encryption.wipe()

    assertThrows(IllegalStateException::class.java) { encryption.encryptForReader(byteArrayOf(1)) }
    assertThrows(IllegalStateException::class.java) {
      encryption.decryptFromReader(firstFromReader.getString("ciphertext").hexToBytes())
    }
  }

  @Test
  fun `establish derives the session from the device key, the reader key and the engagement`() {
    val deviceKey = EphemeralDeviceKey.fromPrivateScalar(vectors.getJSONObject("deviceKey").getString("privateScalar").hexToBytes())
    val readerCoseKey = untag24(decodeCbor(vectors.getJSONObject("readerKey").getString("eReaderKeyBytes").hexToBytes()))
    val deviceEngagementBytes = vectors.getString("deviceEngagement").hexToBytes()

    val transcript = sessionTranscriptBytes(deviceEngagementBytes, readerCoseKey)

    val encryption = SessionEncryption.establish(deviceKey, readerCoseKey, transcript)

    val plaintext = encryption.decryptFromReader(firstFromReader.getString("ciphertext").hexToBytes())
    assertEquals(firstFromReader.getString("plaintext"), plaintext.toHex())
  }
}
