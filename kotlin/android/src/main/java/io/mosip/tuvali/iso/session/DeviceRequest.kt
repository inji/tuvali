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

// The engagement is version 1.0, and a request's major version must not be higher (clause 8.3.2.1.2.1)
private const val SUPPORTED_MAJOR_VERSION = 1

/** The reader's decrypted request (clause 8.3.2.1.2.1): one or more requested documents. */
internal class DeviceRequest(val version: String, val docRequests: List<DocRequest>) {
  companion object {
    /** Parses a request; anything malformed is a [CborDecodingException], answered with status 11. */
    fun decode(plaintext: ByteArray): DeviceRequest {
      val map = decodeCbor(plaintext).asMap("DeviceRequest")
      val version = map.required("version", "DeviceRequest").asText("version")
      checkMajorVersion(version)
      val docRequests = map.required("docRequests", "DeviceRequest").asArray("docRequests")
      if (docRequests.isEmpty()) throw CborDecodingException("DeviceRequest has no docRequests")
      return DeviceRequest(version, docRequests.map(DocRequest::decode))
    }

    // a known major version with an unknown minor version is not an error (clause 8.1)
    private fun checkMajorVersion(version: String) {
      val major = version.substringBefore('.').toIntOrNull()
      if (major != SUPPORTED_MAJOR_VERSION) throw CborDecodingException("Unsupported DeviceRequest version $version")
    }
  }
}

/**
 * One requested document. [nameSpaces] maps each namespace to its requested data elements and
 * their IntentToRetain flag. [itemsRequestBytes] are the ItemsRequest exactly as received, since
 * reader authentication signs those bytes; [readerAuth] is kept as received for that check.
 * requestInfo and unknown keys are ignored (clause 8.3.2.1.2.1).
 */
internal class DocRequest(
  val docType: String,
  val nameSpaces: Map<String, Map<String, Boolean>>,
  val itemsRequestBytes: ByteArray,
  val readerAuth: DataItem?,
) {
  companion object {
    fun decode(item: DataItem): DocRequest {
      val docRequest = item.asMap("DocRequest")
      val itemsRequestBytes = untag24(docRequest.required("itemsRequest", "DocRequest"))
      val itemsRequest = decodeCbor(itemsRequestBytes).asMap("ItemsRequest")
      return DocRequest(
        docType = itemsRequest.required("docType", "ItemsRequest").asText("docType"),
        nameSpaces = decodeNameSpaces(itemsRequest.required("nameSpaces", "ItemsRequest")),
        itemsRequestBytes = itemsRequestBytes,
        readerAuth = docRequest.optional("readerAuth"),
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
