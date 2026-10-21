package io.mosip.tuvali.iso.session

/** SessionData status codes (Table 20); each one ends the session. */
internal object SessionStatus {
  const val ENCRYPTION_ERROR = 10
  const val CBOR_DECODING_ERROR = 11
  const val SESSION_TERMINATION = 20
}
