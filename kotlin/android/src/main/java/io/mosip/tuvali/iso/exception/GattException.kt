package io.mosip.tuvali.iso.exception

internal sealed class GattException(message: String, cause: Throwable? = null) : Exception(message, cause)
