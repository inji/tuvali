package io.mosip.tuvali.testutils

import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.Array
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.Number
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import java.io.ByteArrayOutputStream

/** Small readers for decoded CBOR, so structure tests read like the CDDL they check. */

fun DataItem.valueAt(key: Long): DataItem {
  val keyItem = if (key >= 0) UnsignedInteger(key) else NegativeInteger(key)
  return checkNotNull((this as Map).get(keyItem)) { "No map entry for key $key" }
}

fun DataItem.integerKeys(): List<Long> = (this as Map).keys.map { (it as Number).value.toLong() }

fun DataItem.items(): List<DataItem> = (this as Array).dataItems

fun DataItem.asLong(): Long = (this as Number).value.toLong()

fun DataItem.asText(): String = (this as UnicodeString).string

fun DataItem.asBytes(): ByteArray = (this as ByteString).bytes

/**
 * Encodes with cbor-java's canonical mode (map keys sorted by encoded length, then bytes).
 * cbor-java compares signed bytes, so the order is only right for keys whose encoding starts
 * below 0x80 (integers and strings); fine for ISO structures, which use no array or map keys.
 */
fun encodeCanonicalCbor(item: DataItem): ByteArray {
  val output = ByteArrayOutputStream()
  CborEncoder(output).encode(item)
  return output.toByteArray()
}
