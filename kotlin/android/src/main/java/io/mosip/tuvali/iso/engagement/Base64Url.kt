package io.mosip.tuvali.iso.engagement

private const val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private const val SIX_BITS = 0x3f

/**
 * RFC 4648 base64url without padding. Written out because java.util.Base64 needs API 26
 * (Tuvali supports 21) and android.util.Base64 is not available in JVM unit tests.
 */
internal fun encodeBase64UrlWithoutPadding(bytes: ByteArray): String = buildString {
  for (groupStart in bytes.indices step 3) {
    val groupSize = minOf(3, bytes.size - groupStart)
    // pack up to 3 bytes into the top of a 24-bit number
    var bits = 0
    for (offset in 0 until 3) {
      val byte = if (offset < groupSize) bytes[groupStart + offset].toInt() and 0xff else 0
      bits = (bits shl 8) or byte
    }
    // each input byte yields at least one more output character: 1 → 2, 2 → 3, 3 → 4
    for (characterIndex in 0..groupSize) {
      append(BASE64URL_ALPHABET[(bits shr (18 - 6 * characterIndex)) and SIX_BITS])
    }
  }
}
