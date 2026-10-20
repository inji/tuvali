package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.exception.SessionDecryptionException
import java.nio.ByteBuffer
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12
private const val LARGEST_MESSAGE_COUNTER = 0xFFFF_FFFFL

// IV = 8-byte identifier || 4-byte big-endian message counter (clause 9.1.1.5)
private val READER_IDENTIFIER = ByteArray(8)
private val MDOC_IDENTIFIER = ByteArray(8).also { it[7] = 1 }

/**
 * Encrypts our responses and decrypts the reader's requests with AES-256-GCM (clause 9.1.1.5).
 *
 * Each direction has its own key and message counter; the first message uses counter 1 and a
 * counter value is never reused. Data is ciphertext || 16-byte tag, with empty AAD. A reader
 * message that fails to decrypt leaves the counter unchanged; the session then ends anyway.
 *
 * One session owns an instance; it is not meant to be shared between threads.
 */
internal class SessionEncryption(
  private val keys: SessionKeys,
  // starting counters other than 0 are only for tests that reach the counter limit
  private var lastCounterFromReader: Long = 0,
  private var lastCounterToReader: Long = 0,
) {
  @Volatile
  private var wiped = false

  fun encryptForReader(plaintext: ByteArray): ByteArray {
    val counter = nextCounter(lastCounterToReader)
    val ciphertext = runGcm(forEncryption = true, keys.skDevice, iv(MDOC_IDENTIFIER, counter), plaintext)
    lastCounterToReader = counter
    return ciphertext
  }

  fun decryptFromReader(data: ByteArray): ByteArray {
    val counter = nextCounter(lastCounterFromReader)
    val plaintext = try {
      runGcm(forEncryption = false, keys.skReader, iv(READER_IDENTIFIER, counter), data)
    } catch (exception: InvalidCipherTextException) {
      throw SessionDecryptionException("Reader message $counter failed authentication", exception)
    }
    lastCounterFromReader = counter
    return plaintext
  }

  /** Overwrites both session keys; any later use fails. */
  fun wipe() {
    wiped = true
    keys.wipe()
  }

  private fun nextCounter(lastCounter: Long): Long {
    check(!wiped) { "Session keys were wiped" }
    check(lastCounter < LARGEST_MESSAGE_COUNTER) { "Message counter is used up; start a new session" }
    return lastCounter + 1
  }

  private fun iv(identifier: ByteArray, counter: Long): ByteArray =
    ByteBuffer.allocate(GCM_IV_BYTES).put(identifier).putInt(counter.toInt()).array()

  private fun runGcm(forEncryption: Boolean, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
    val cipher = GCMBlockCipher(AESEngine()).apply {
      init(forEncryption, AEADParameters(KeyParameter(key), GCM_TAG_BITS, iv))
    }
    val output = ByteArray(cipher.getOutputSize(input.size))
    val written = cipher.processBytes(input, 0, input.size, output, 0)
    val total = written + cipher.doFinal(output, written)
    return output.copyOf(total)
  }

  companion object {
    /**
     * Sets up the session after the reader's first message: ECDH with the reader key, the
     * transcript from the engagement and the reader key bytes as received, then the session keys.
     */
    fun establish(
      deviceKey: EphemeralDeviceKey,
      readerCoseKey: ByteArray,
      deviceEngagementBytes: ByteArray,
    ): SessionEncryption {
      val readerKey = ReaderPublicKey.fromCoseKey(readerCoseKey)
      val secret = sharedSecret(deviceKey, readerKey)
      try {
        return SessionEncryption(deriveSessionKeys(secret, sessionTranscriptBytes(deviceEngagementBytes, readerCoseKey)))
      } finally {
        secret.fill(0)
      }
    }
  }
}
