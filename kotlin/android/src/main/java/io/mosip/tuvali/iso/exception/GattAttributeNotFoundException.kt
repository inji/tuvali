package io.mosip.tuvali.iso.exception

internal class GattAttributeNotFoundException(description: String) : GattException("$description not found")
