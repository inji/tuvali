package io.mosip.tuvali.iso.exception

/** A reader message could not be decrypted and authenticated; the session must end (status 10). */
internal class SessionDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
