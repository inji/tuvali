package io.mosip.tuvali.testutils

fun String.hexToBytes(): ByteArray {
  require(length % 2 == 0) { "Hex string must have an even length" }
  return ByteArray(length / 2) { index -> substring(2 * index, 2 * index + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
