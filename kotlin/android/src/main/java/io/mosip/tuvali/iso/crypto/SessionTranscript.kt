package io.mosip.tuvali.iso.crypto

import io.mosip.tuvali.iso.cbor.cborArrayOf
import io.mosip.tuvali.iso.cbor.encodeCbor
import io.mosip.tuvali.iso.cbor.tag24

/**
 * SessionTranscriptBytes (clause 9.1.5.1):
 * #6.24(bstr .cbor [DeviceEngagementBytes, EReaderKeyBytes, Handover]), where the handover is
 * null for QR engagement.
 *
 * The engagement and reader key bytes are wrapped exactly as given: the engagement as put in the
 * QR code, and the reader's COSE_Key as received inside EReaderKeyBytes, never decoded and re-encoded.
 */
internal fun sessionTranscriptBytes(
  deviceEngagementBytes: ByteArray,
  readerCoseKey: ByteArray,
  handover: Handover = Handover.Qr,
): ByteArray {
  val transcript = cborArrayOf(tag24(deviceEngagementBytes), tag24(readerCoseKey), handover.toCbor())
  return encodeCbor(tag24(encodeCbor(transcript)))
}
