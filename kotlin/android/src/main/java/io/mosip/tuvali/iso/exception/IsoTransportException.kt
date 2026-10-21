package io.mosip.tuvali.iso.exception

/** The reader broke the ISO 18013-5 BLE transport rules (service layout, framing, size); the session must end. */
internal class IsoTransportException(message: String) : Exception(message)
