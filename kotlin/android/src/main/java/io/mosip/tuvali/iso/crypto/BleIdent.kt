package io.mosip.tuvali.iso.crypto

private const val IDENT_BYTES = 16
private const val INFO_BLE_IDENT = "BLEIdent"

/**
 * The value the reader's Ident characteristic must hold (clause 8.3.3.1.1.3):
 * HKDF-SHA256 over EDeviceKeyBytes with no salt, info "BLEIdent", 16 bytes.
 */
internal fun bleIdent(eDeviceKeyBytes: ByteArray): ByteArray =
  hkdfSha256(eDeviceKeyBytes, salt = null, info = INFO_BLE_IDENT, length = IDENT_BYTES)
