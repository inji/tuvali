package io.mosip.tuvali.iso.exception

/** CBOR received from a reader is malformed or not allowed; ends the session with status 11. */
class CborDecodingException(message: String, cause: Throwable? = null) : Exception(message, cause)
