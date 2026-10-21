package io.mosip.tuvali.iso.exception

/** Failures of the coroutine GATT client used by the ISO 18013-5 session. */
sealed class GattException(message: String, cause: Throwable? = null) : Exception(message, cause)
