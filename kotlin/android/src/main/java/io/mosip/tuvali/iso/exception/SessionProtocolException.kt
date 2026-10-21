package io.mosip.tuvali.iso.exception

/** The reader sent a session message out of order, e.g. a second SessionEstablishment; the session must end. */
class SessionProtocolException(message: String) : Exception(message)
