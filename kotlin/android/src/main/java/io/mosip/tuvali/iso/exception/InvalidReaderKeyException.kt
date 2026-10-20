package io.mosip.tuvali.iso.exception

/** The reader's ephemeral key (EReaderKey) is not a usable P-256 public key; the session must end. */
class InvalidReaderKeyException(message: String) : Exception(message)
