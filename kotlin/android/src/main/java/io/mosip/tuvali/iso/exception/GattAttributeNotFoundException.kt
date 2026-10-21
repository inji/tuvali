package io.mosip.tuvali.iso.exception

/** The remote GATT server has no such service, characteristic or descriptor. */
internal class GattAttributeNotFoundException(description: String) : GattException("$description not found")
