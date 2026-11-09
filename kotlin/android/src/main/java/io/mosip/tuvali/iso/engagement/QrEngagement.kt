package io.mosip.tuvali.iso.engagement

private const val QR_SCHEME = "mdoc:"

/**
 * The text of the QR code for this engagement (clause 8.2.2.3): "mdoc:" followed by the engagement
 * bytes in base64url without padding. A QR engagement must list at least one retrieval method
 * (clause 8.2.1.1), since the reader has no other way to learn how to connect.
 */
internal fun DeviceEngagement.toQrText(): String {
  require(retrievalMethods.isNotEmpty()) { "A QR engagement must list at least one retrieval method" }
  return QR_SCHEME + encodeBase64UrlWithoutPadding(bytes)
}
