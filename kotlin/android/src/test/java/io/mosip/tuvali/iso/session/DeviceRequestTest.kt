package io.mosip.tuvali.iso.session

import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.cborMapOf
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.tag24
import io.mosip.tuvali.iso.exception.CborDecodingException
import io.mosip.tuvali.iso.readerauth.ReaderTrust
import io.mosip.tuvali.testutils.SessionTestVectors
import io.mosip.tuvali.testutils.hexToBytes
import io.mosip.tuvali.testutils.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** DeviceRequest, DocRequest and ItemsRequest: ISO 18013-5 clause 8.3.2.1.2.1. */
class DeviceRequestTest {

  private val session = SessionTestVectors.session.getJSONObject("session")

  @Test
  fun `decode reads every docRequest of the test vector request`() {
    val request = decodeRequest(session.getString("twoDocRequest").hexToBytes())

    assertEquals("1.0", request.version)
    assertEquals(listOf("org.iso.18013.5.1.mDL", "org.example.test.1"), request.docRequests.map { it.docType })
  }

  @Test
  fun `decode reads each requested element with its IntentToRetain flag`() {
    val request = decodeRequest(session.getString("twoDocRequest").hexToBytes())

    val mdlElements = request.docRequests[0].nameSpaces.getValue("org.iso.18013.5.1")

    assertEquals(mapOf("family_name" to true, "portrait" to false, "age_over_18" to false), mdlElements)
  }

  @Test
  fun `decode keeps each ItemsRequest's bytes exactly as received`() {
    val request = decodeRequest(session.getString("twoDocRequest").hexToBytes())

    assertEquals(session.getString("mdlItemsRequestBytes"), request.docRequests[0].itemsRequestBytes.toHex())
    assertEquals(session.getString("secondDocItemsRequestBytes"), request.docRequests[1].itemsRequestBytes.toHex())
  }

  @Test
  fun `decode ignores requestInfo and leaves readerAuth empty when the reader did not sign`() {
    // the second docRequest carries requestInfo {"purpose": "test"} and no readerAuth
    val request = decodeRequest(session.getString("twoDocRequest").hexToBytes())

    assertEquals(setOf("org.example.test.ns"), request.docRequests[1].nameSpaces.keys)
    assertEquals(ReaderTrust.NotSigned, request.docRequests[1].readerTrust)
  }

  @Test
  fun `decode hands each readerAuth and its ItemsRequest bytes to the check and keeps the result`() {
    // a placeholder COSE_Sign1 array; checking it is reader authentication's job
    val signedDocRequest = cborMapOf("itemsRequest" to tag24(itemsRequest()), "readerAuth" to cborArrayOf(1L, 2L))
    val checked = mutableListOf<Pair<Boolean, String>>()

    val request = DeviceRequest.decode(deviceRequest(docRequest = signedDocRequest)) { readerAuth, itemsRequestBytes ->
      checked += (readerAuth != null) to itemsRequestBytes.toHex()
      ReaderTrust.Invalid("placeholder")
    }

    assertEquals(listOf(true to itemsRequest().toHex()), checked)
    assertTrue(request.docRequests[0].readerTrust is ReaderTrust.Invalid)
  }

  @Test
  fun `decode accepts a later minor version`() {
    // clause 8.1: a known major version with an unknown minor version is not an error
    val request = decodeRequest(deviceRequest(version = "1.1"))

    assertEquals("1.1", request.version)
  }

  @Test
  fun `decode throws CborDecodingException for a higher major version than the engagement`() {
    // the engagement is version 1.0, and the request's major version must not be higher
    assertThrows(CborDecodingException::class.java) { decodeRequest(deviceRequest(version = "2.0")) }
  }

  @Test
  fun `decode throws CborDecodingException when docRequests is empty`() {
    val noDocuments = encodeCbor(cborMapOf("version" to "1.0", "docRequests" to cborArrayOf()))

    assertThrows(CborDecodingException::class.java) { decodeRequest(noDocuments) }
  }

  @Test
  fun `decode throws CborDecodingException when itemsRequest is not tag 24`() {
    val untagged = deviceRequest(docRequest = cborMapOf("itemsRequest" to itemsRequest()))

    assertThrows(CborDecodingException::class.java) { decodeRequest(untagged) }
  }

  @Test
  fun `decode throws CborDecodingException when nameSpaces is empty`() {
    val noNameSpaces = itemsRequest(nameSpaces = cborMapOf())

    assertThrows(CborDecodingException::class.java) { decodeRequest(deviceRequest(items = noNameSpaces)) }
  }

  @Test
  fun `decode throws CborDecodingException when a namespace requests no elements`() {
    val emptyElements = itemsRequest(nameSpaces = cborMapOf("org.iso.18013.5.1" to cborMapOf()))

    assertThrows(CborDecodingException::class.java) { decodeRequest(deviceRequest(items = emptyElements)) }
  }

  @Test
  fun `decode throws CborDecodingException when IntentToRetain is not a boolean`() {
    val notBoolean = itemsRequest(nameSpaces = cborMapOf("org.iso.18013.5.1" to cborMapOf("family_name" to 1L)))

    assertThrows(CborDecodingException::class.java) { decodeRequest(deviceRequest(items = notBoolean)) }
  }

  @Test
  fun `decode throws CborDecodingException when docType is missing`() {
    val noDocType = encodeCbor(cborMapOf("nameSpaces" to cborMapOf("ns" to cborMapOf("e" to true))))

    assertThrows(CborDecodingException::class.java) { decodeRequest(deviceRequest(items = noDocType)) }
  }

  @Test
  fun `decode ignores top-level keys it does not know`() {
    val withExtra = encodeCbor(
      cborMapOf(
        "version" to "1.0",
        "docRequests" to cborArrayOf(cborMapOf("itemsRequest" to tag24(itemsRequest()))),
        "x" to 1L,
      )
    )

    val request = decodeRequest(withExtra)

    assertEquals(1, request.docRequests.size)
  }

  @Test
  fun `decode throws CborDecodingException when the request is not a map`() {
    assertThrows(CborDecodingException::class.java) { decodeRequest("80".hexToBytes()) }
  }

  private fun itemsRequest(
    nameSpaces: Any = cborMapOf("org.iso.18013.5.1" to cborMapOf("family_name" to true)),
  ): ByteArray = encodeCbor(cborMapOf("docType" to "org.iso.18013.5.1.mDL", "nameSpaces" to nameSpaces))

  private fun deviceRequest(
    version: String = "1.0",
    items: ByteArray = itemsRequest(),
    docRequest: Any = cborMapOf("itemsRequest" to tag24(items)),
  ): ByteArray = encodeCbor(cborMapOf("version" to version, "docRequests" to cborArrayOf(docRequest)))

  /** Decodes with a check that only tells signed from unsigned requests. */
  private fun decodeRequest(plaintext: ByteArray): DeviceRequest =
    DeviceRequest.decode(plaintext) { readerAuth, _ ->
      if (readerAuth == null) ReaderTrust.NotSigned else ReaderTrust.Invalid("not checked here")
    }
}
