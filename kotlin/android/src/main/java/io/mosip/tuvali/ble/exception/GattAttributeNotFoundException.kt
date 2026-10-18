package io.mosip.tuvali.ble.exception

/** The remote GATT server has no such service, characteristic or descriptor. */
class GattAttributeNotFoundException(description: String) : GattException("$description not found")
