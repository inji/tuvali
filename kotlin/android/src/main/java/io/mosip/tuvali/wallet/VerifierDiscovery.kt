package io.mosip.tuvali.wallet

// iOS verifiers cannot advertise service data, so they are matched by the QR name and use the QR key
internal fun matchingVerifierKey(
  advertisementPayload: ByteArray?,
  scanResponsePayload: ByteArray?,
  localName: String?,
  expectedAdvPayload: ByteArray?,
  expectedName: String?,
  qrVerifierKey: ByteArray?
): ByteArray? {
  if (advertisementPayload != null) {
    if (expectedAdvPayload == null || !(expectedAdvPayload contentEquals advertisementPayload)) return null
    return scanResponsePayload?.let { advertisementPayload.takeLast(5).toByteArray() + it }
  }
  if (localName == null || localName != expectedName) return null
  return qrVerifierKey
}
