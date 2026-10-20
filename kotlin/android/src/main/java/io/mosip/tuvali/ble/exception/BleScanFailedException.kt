package io.mosip.tuvali.ble.exception

/** Android could not start a Bluetooth LE scan; [errorCode] is one of ScanCallback's SCAN_FAILED_* values. */
class BleScanFailedException(val errorCode: Int) : GattException("Bluetooth LE scan failed with code $errorCode")
