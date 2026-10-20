package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.tag24

/**
 * SessionTranscriptBytes for QR engagement (clause 9.1.5.1):
 * #6.24(bstr .cbor [DeviceEngagementBytes, EReaderKeyBytes, null]).
 *
 * Both inputs are wrapped exactly as given: the engagement bytes as put in the QR code, and the
 * reader's COSE_Key bytes as received inside EReaderKeyBytes, never decoded and re-encoded.
 */
internal fun sessionTranscriptBytes(deviceEngagementBytes: ByteArray, readerCoseKey: ByteArray): ByteArray {
  // the handover is null for QR engagement (QRHandover)
  val transcript = cborArrayOf(tag24(deviceEngagementBytes), tag24(readerCoseKey), null)
  return encodeCbor(tag24(encodeCbor(transcript)))
}
