package io.mosip.tuvali.iso.cbor

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.CborException
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.Special
import co.nstant.`in`.cbor.model.UnicodeString
import co.nstant.`in`.cbor.model.UnsignedInteger
import io.mosip.tuvali.iso.exception.CborDecodingException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * CBOR helpers for the ISO 18013-5 session, on top of cbor-java.
 *
 * Encoding (ISO 18013-5 clause 8.1):
 * 1. Integers and lengths are always in the shortest form, and lengths are always definite.
 * 2. Map keys stay in insertion order (sorting is not required).
 *
 * Decoding data received from a reader:
 * 3. Exactly one item, and no duplicate map keys.
 * 4. Bounded nesting, and no length that runs past the input.
 */

const val MAX_CBOR_NESTING_DEPTH = 64
private const val TAG_ENCODED_CBOR = 24L

// CBOR initial byte: top 3 bits = major type, low 5 bits = additional information (RFC 7049 2.1)
private const val MAJOR_TYPE_SHIFT = 5
private const val ADDITIONAL_INFO_MASK = 0x1f
private const val MAJOR_TYPE_UNSIGNED_INTEGER = 0
private const val MAJOR_TYPE_NEGATIVE_INTEGER = 1
private const val MAJOR_TYPE_BYTE_STRING = 2
private const val MAJOR_TYPE_TEXT_STRING = 3
private const val MAJOR_TYPE_ARRAY = 4
private const val MAJOR_TYPE_MAP = 5
private const val MAJOR_TYPE_TAG = 6
private const val MAJOR_TYPE_SIMPLE_OR_FLOAT = 7
private const val LARGEST_INLINE_ARGUMENT = 23
private const val ONE_BYTE_ARGUMENT = 24
private const val TWO_BYTE_ARGUMENT = 25
private const val FOUR_BYTE_ARGUMENT = 26
private const val EIGHT_BYTE_ARGUMENT = 27
private const val INDEFINITE_LENGTH_ARGUMENT = 31
private const val BREAK_BYTE = 0xff

/**
 * Encodes [item], keeping map keys in insertion order. Callers add keys in sorted order where a
 * structure must be canonical (e.g. DeviceEngagement). cbor-java's own canonical mode is not used
 * on purpose: its key sort compares signed bytes, so keys encoded from 0x80 up would be misordered.
 */
fun encodeCbor(item: DataItem): ByteArray {
  val outputStream = ByteArrayOutputStream()
  CborEncoder(outputStream).nonCanonical().encode(item)
  return outputStream.toByteArray()
}

/**
 * Decodes one CBOR item received from a reader.
 *
 * Note: we are lenient about *how* a reader writes something and strict about *what* it means.
 * A number in a longer form than needed, or an indefinite-length list, is accepted: its meaning
 * is clear, and anything used in cryptography is taken from the tag 24 bytes exactly as received,
 * so it cannot change a hash or a signature check. Duplicate map keys (unclear meaning) and
 * malformed or hostile input (truncated, trailing bytes, too deep, impossible lengths) are
 * rejected with [CborDecodingException]. Everything we send ourselves follows clause 8.1 exactly.
 */
fun decodeCbor(encodedCbor: ByteArray): DataItem {
  checkStructure(encodedCbor)
  val decodedItems = try {
    CborDecoder(ByteArrayInputStream(encodedCbor)).apply { isRejectDuplicateKeys = true }.decode()
  } catch (exception: CborException) {
    throw CborDecodingException("Invalid CBOR: ${exception.message}", exception)
  } catch (exception: RuntimeException) {
    throw CborDecodingException("Invalid CBOR: ${exception.message}", exception)
  }
  val decodedItem = decodedItems.singleOrNull()
    ?: throw CborDecodingException("Expected exactly one CBOR item, found ${decodedItems.size}")
  return withDefiniteLengths(decodedItem)
}

/**
 * cbor-java remembers that an item arrived with an indefinite length and would encode it the
 * same way again (and keeps the closing break inside arrays). Clause 8.1 requires definite
 * lengths, so decoded strings, arrays and maps are rebuilt as definite-length copies, keeping
 * their tags. Nesting is already bounded by [checkStructure].
 */
private fun withDefiniteLengths(item: DataItem): DataItem {
  val definiteItem: DataItem = when (item) {
    is ByteString -> ByteString(item.bytes)
    is UnicodeString -> UnicodeString(item.string)
    is CborArray -> CborArray().apply {
      item.dataItems
        .filterNot { element -> element == Special.BREAK }
        .forEach { element -> add(withDefiniteLengths(element)) }
    }
    is CborMap -> CborMap().apply {
      item.keys.forEach { key -> put(withDefiniteLengths(key), withDefiniteLengths(item.get(key))) }
    }
    else -> return item
  }
  item.tag?.let { tag -> definiteItem.setTag(tag) }
  return definiteItem
}

fun cborArrayOf(vararg elements: Any?): DataItem {
  val arrayBuilder = CborBuilder().addArray()
  elements.forEach { element -> arrayBuilder.add(toDataItem(element)) }
  return arrayBuilder.end().build()[0]
}

/** Builds a map in the given key order. Duplicate keys are a programming error (clause 8.1). */
fun cborMapOf(vararg entries: Pair<Any, Any?>): DataItem {
  val keyItems = entries.map { (key, _) -> toDataItem(key) }
  require(keyItems.toSet().size == keyItems.size) { "Duplicate key in CBOR map" }
  val mapBuilder = CborBuilder().addMap()
  entries.forEach { (key, value) -> mapBuilder.put(toDataItem(key), toDataItem(value)) }
  return mapBuilder.end().build()[0]
}

/** #6.24(bstr .cbor item): encodes [item] and wraps the bytes as a tag 24 byte string. */
fun tag24(item: DataItem): ByteString = tag24(encodeCbor(item))

fun tag24(encodedCbor: ByteArray): ByteString = ByteString(encodedCbor).apply { setTag(TAG_ENCODED_CBOR) }

fun untag24(item: DataItem): ByteArray {
  if (item !is ByteString || item.tag?.value != TAG_ENCODED_CBOR) {
    throw CborDecodingException("Expected a tag 24 byte string, found ${item.majorType} with tag ${item.tag?.value}")
  }
  return item.bytes
}

fun decodeTag24(item: DataItem): DataItem = decodeCbor(untag24(item))

private fun toDataItem(value: Any?): DataItem = when (value) {
  null -> SimpleValue.NULL
  is DataItem -> value
  is Boolean -> if (value) SimpleValue.TRUE else SimpleValue.FALSE
  is Int -> toDataItem(value.toLong())
  is Long -> if (value >= 0) UnsignedInteger(value) else NegativeInteger(value)
  is String -> UnicodeString(value)
  is ByteArray -> ByteString(value)
  else -> throw IllegalArgumentException("Unsupported CBOR value type: ${value.javaClass.name}")
}

/**
 * Walks the item headers without recursion before cbor-java decodes them, so hostile input
 * fails with [CborDecodingException] instead of a stack overflow or a huge allocation.
 * Checks: one complete top-level item, no trailing bytes, nesting within
 * [MAX_CBOR_NESTING_DEPTH], and no length or count larger than the remaining input.
 */
private fun checkStructure(encodedCbor: ByteArray) {
  if (encodedCbor.isEmpty()) throw CborDecodingException("Empty CBOR input")

  val indefiniteLength = -1L
  // For each array, map or tag still open: how many items it is still waiting for
  // (indefiniteLength for containers that end with a break byte instead of a count)
  val itemsStillExpected = ArrayDeque<Long>()
  var readPosition = 0

  fun bytesRemaining(): Long = (encodedCbor.size - readPosition).toLong()

  fun readArgument(additionalInfo: Int): Long {
    val argumentByteCount = when (additionalInfo) {
      in 0..LARGEST_INLINE_ARGUMENT -> return additionalInfo.toLong()
      ONE_BYTE_ARGUMENT -> 1
      TWO_BYTE_ARGUMENT -> 2
      FOUR_BYTE_ARGUMENT -> 4
      EIGHT_BYTE_ARGUMENT -> 8
      INDEFINITE_LENGTH_ARGUMENT -> return indefiniteLength
      else -> throw CborDecodingException("Reserved additional information $additionalInfo")
    }
    if (bytesRemaining() < argumentByteCount) throw CborDecodingException("Truncated CBOR argument")
    var argument = 0L
    repeat(argumentByteCount) { argument = (argument shl 8) or (encodedCbor[readPosition++].toLong() and 0xff) }
    // A 64-bit argument above Long.MAX_VALUE becomes negative and fails every length check below
    return argument
  }

  fun markItemComplete() {
    while (itemsStillExpected.isNotEmpty()) {
      val expectedInInnermost = itemsStillExpected.last()
      if (expectedInInnermost == indefiniteLength) return
      if (expectedInInnermost > 1) {
        itemsStillExpected[itemsStillExpected.lastIndex] = expectedInInnermost - 1
        return
      }
      // The innermost container is now complete, which completes one item of its parent
      itemsStillExpected.removeLast()
    }
  }

  fun openContainer(itemCount: Long) {
    if (itemCount == 0L) {
      markItemComplete()
      return
    }
    itemsStillExpected.addLast(itemCount)
    if (itemsStillExpected.size > MAX_CBOR_NESTING_DEPTH) {
      throw CborDecodingException("CBOR nesting deeper than $MAX_CBOR_NESTING_DEPTH")
    }
  }

  do {
    if (bytesRemaining() < 1) throw CborDecodingException("Truncated CBOR input")
    val initialByte = encodedCbor[readPosition++].toInt() and 0xff
    if (initialByte == BREAK_BYTE) {
      if (itemsStillExpected.lastOrNull() != indefiniteLength) throw CborDecodingException("Unexpected CBOR break")
      itemsStillExpected.removeLast()
      markItemComplete()
      continue
    }
    val majorType = initialByte ushr MAJOR_TYPE_SHIFT
    val argument = readArgument(initialByte and ADDITIONAL_INFO_MASK)
    val isIndefinite = argument == indefiniteLength
    if (isIndefinite && majorType !in MAJOR_TYPE_BYTE_STRING..MAJOR_TYPE_MAP) {
      throw CborDecodingException("Indefinite length not allowed for major type $majorType")
    }

    when (majorType) {
      MAJOR_TYPE_UNSIGNED_INTEGER, MAJOR_TYPE_NEGATIVE_INTEGER, MAJOR_TYPE_SIMPLE_OR_FLOAT -> markItemComplete()
      MAJOR_TYPE_BYTE_STRING, MAJOR_TYPE_TEXT_STRING -> if (isIndefinite) {
        openContainer(indefiniteLength)
      } else {
        val stringLength = argument
        if (stringLength < 0 || stringLength > bytesRemaining()) throw CborDecodingException("CBOR string longer than the input")
        readPosition += stringLength.toInt()
        markItemComplete()
      }
      MAJOR_TYPE_ARRAY -> {
        val elementCount = argument
        if (!isIndefinite && (elementCount < 0 || elementCount > bytesRemaining())) {
          throw CborDecodingException("CBOR array count larger than the input")
        }
        openContainer(elementCount)
      }
      MAJOR_TYPE_MAP -> {
        val entryCount = argument
        if (!isIndefinite && (entryCount < 0 || entryCount > bytesRemaining() / 2)) {
          throw CborDecodingException("CBOR map count larger than the input")
        }
        // each entry is two items: a key and a value
        openContainer(if (isIndefinite) indefiniteLength else entryCount * 2)
      }
      MAJOR_TYPE_TAG -> openContainer(1)
    }
  } while (itemsStillExpected.isNotEmpty())

  if (readPosition != encodedCbor.size) throw CborDecodingException("Trailing bytes after the CBOR item")
}
