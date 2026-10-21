package io.mosip.tuvali.iso.cbor

import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import io.mosip.tuvali.iso.exception.CborDecodingException

// Typed readers for decoded CBOR received from a reader. Each names what it expected, and a
// wrong type or a missing key is a CborDecodingException (status 11, "CBOR decoding").

internal fun DataItem.asMap(what: String): CborMap =
  this as? CborMap ?: throw CborDecodingException("$what must be a map")

internal fun DataItem.asArray(what: String): List<DataItem> =
  (this as? CborArray)?.dataItems ?: throw CborDecodingException("$what must be an array")

internal fun DataItem.asText(what: String): String =
  (this as? UnicodeString)?.string ?: throw CborDecodingException("$what must be a text string")

internal fun DataItem.asBytes(what: String): ByteArray =
  (this as? ByteString)?.bytes ?: throw CborDecodingException("$what must be a byte string")

internal fun DataItem.asUnsignedInt(what: String): Long =
  (this as? UnsignedInteger)?.value?.toLong() ?: throw CborDecodingException("$what must be an unsigned integer")

internal fun DataItem.asBoolean(what: String): Boolean = when (this) {
  SimpleValue.TRUE -> true
  SimpleValue.FALSE -> false
  else -> throw CborDecodingException("$what must be a boolean")
}

/** The value under text [key], or null when the key is absent. */
internal fun CborMap.optional(key: String): DataItem? = get(UnicodeString(key))

/** The value under text [key]; a missing key is a CborDecodingException. */
internal fun CborMap.required(key: String, what: String): DataItem =
  optional(key) ?: throw CborDecodingException("$what has no \"$key\"")
