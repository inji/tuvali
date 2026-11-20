package io.mosip.tuvali.iso.session

import co.nstant.`in`.cbor.model.DataItem
import io.mosip.tuvali.iso.cbor.asArray
import io.mosip.tuvali.iso.cbor.asBoolean
import io.mosip.tuvali.iso.cbor.asMap
import io.mosip.tuvali.iso.cbor.asText
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.optional
import io.mosip.tuvali.iso.cbor.required
import io.mosip.tuvali.iso.cbor.untag24
import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.iso.readerauth.ReaderTrust

// The engagement is version 1.0, and a request's major version must not be higher (clause 8.3.2.1.2.1)
private const val SUPPORTED_MAJOR_VERSION = 1

/** Checks one DocRequest's readerAuth (null when unsigned) against its ItemsRequest bytes. */
internal typealias ReaderAuthCheck = (readerAuth: DataItem?, itemsRequestBytes: ByteArray) -> ReaderTrust

/** The reader's decrypted request (clause 8.3.2.1.2.1): one or more requested documents. */
internal class DeviceRequest(val version: String, val docRequests: List<DocRequest>) {
  companion object {
    /**
     * Parses a request and checks each document's reader authentication with [checkReaderAuth].
     * Anything malformed is a [CborDecodingException], answered with status 11.
     */
    fun decode(plaintext: ByteArray, checkReaderAuth: ReaderAuthCheck): DeviceRequest {
      val map = decodeCbor(plaintext).asMap("DeviceRequest")
      val version = map.required("version", "DeviceRequest").asText("version")
      checkMajorVersion(version)
      val docRequests = map.required("docRequests", "DeviceRequest").asArray("docRequests")
      if (docRequests.isEmpty()) throw CborDecodingException("DeviceRequest has no docRequests")
      return DeviceRequest(version, docRequests.map { DocRequest.decode(it, checkReaderAuth) })
    }

    // a known major version with an unknown minor version is not an error (clause 8.1)
    private fun checkMajorVersion(version: String) {
      val parts = version.split('.')
      if (parts.size != 2 || parts.any { it.isEmpty() || it.any { character -> character !in '0'..'9' } }) {
        throw CborDecodingException("Malformed DeviceRequest version $version")
      }
      val major = parts[0].toIntOrNull()
      if (major != SUPPORTED_MAJOR_VERSION) throw CborDecodingException("Unsupported DeviceRequest version $version")
    }
  }
}

/**
 * One requested document. [nameSpaces] maps each namespace to its requested data elements and
 * their IntentToRetain flag. [itemsRequestBytes] are the ItemsRequest exactly as received, since
 * reader authentication signs those bytes; [readerTrust] is the result of checking that signature.
 * requestInfo and unknown keys are ignored (clause 8.3.2.1.2.1).
 */
internal class DocRequest(
  val docType: String,
  val nameSpaces: Map<String, Map<String, Boolean>>,
  val itemsRequestBytes: ByteArray,
  val readerTrust: ReaderTrust,
) {
  companion object {
    fun decode(item: DataItem, checkReaderAuth: ReaderAuthCheck): DocRequest {
      val docRequest = item.asMap("DocRequest")
      val itemsRequestBytes = untag24(docRequest.required("itemsRequest", "DocRequest"))
      val itemsRequest = decodeCbor(itemsRequestBytes).asMap("ItemsRequest")
      return DocRequest(
        docType = itemsRequest.required("docType", "ItemsRequest").asText("docType"),
        nameSpaces = decodeNameSpaces(itemsRequest.required("nameSpaces", "ItemsRequest")),
        itemsRequestBytes = itemsRequestBytes,
        readerTrust = checkReaderAuth(docRequest.optional("readerAuth"), itemsRequestBytes),
      )
    }

    private fun decodeNameSpaces(item: DataItem): Map<String, Map<String, Boolean>> {
      val nameSpaces = item.asMap("nameSpaces")
      if (nameSpaces.keys.isEmpty()) throw CborDecodingException("nameSpaces is empty")
      return nameSpaces.keys.associate { nameSpace ->
        val elements = nameSpaces.get(nameSpace).asMap("DataElements")
        if (elements.keys.isEmpty()) throw CborDecodingException("No data elements requested in a namespace")
        nameSpace.asText("NameSpace") to elements.keys.associate { element ->
          element.asText("DataElementIdentifier") to elements.get(element).asBoolean("IntentToRetain")
        }
      }
    }
  }
}
