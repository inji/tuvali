package io.mosip.tuvali.iso.exception

/**
 * Android could not start a Bluetooth LE scan; [errorCode] is one of ScanCallback's SCAN_FAILED_* values.
 * Not a [GattException]: scanning happens before any GATT connection.
 */
class BleScanFailedException(val errorCode: Int) : Exception("Bluetooth LE scan failed with code $errorCode")
