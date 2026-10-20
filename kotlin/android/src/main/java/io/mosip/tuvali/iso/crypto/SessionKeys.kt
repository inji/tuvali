package io.mosip.tuvali.iso.crypto

import java.security.MessageDigest

private const val SESSION_KEY_BYTES = 32
private const val INFO_READER_KEY = "SKReader"
private const val INFO_DEVICE_KEY = "SKDevice"

/** SKReader encrypts the reader's requests, SKDevice our responses (clause 9.1.1.5). */
internal class SessionKeys(val skReader: ByteArray, val skDevice: ByteArray) {
  /** Overwrites both keys with zeros. */
  fun wipe() {
    skReader.fill(0)
    skDevice.fill(0)
  }
}

/** HKDF-SHA256 over ZAB with salt SHA-256(SessionTranscriptBytes), 32 bytes each (clause 9.1.1.5). */
internal fun deriveSessionKeys(sharedSecret: ByteArray, sessionTranscriptBytes: ByteArray): SessionKeys {
  val salt = MessageDigest.getInstance("SHA-256").digest(sessionTranscriptBytes)
  return SessionKeys(
    skReader = hkdfSha256(sharedSecret, salt, INFO_READER_KEY, SESSION_KEY_BYTES),
    skDevice = hkdfSha256(sharedSecret, salt, INFO_DEVICE_KEY, SESSION_KEY_BYTES),
  )
}
