package io.mosip.tuvali.iso.crypto

import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.SimpleValue

/**
 * How device engagement happened, which decides the Handover element of the session transcript
 * (clause 9.1.5.1). Only QR exists today; NFC engagement would add an NFCHandover case carrying
 * the Handover Select (and, if negotiated, Request) message bytes.
 */
internal sealed class Handover {
  /** QRHandover = null. */
  object Qr : Handover()

  fun toCbor(): DataItem = when (this) {
    Qr -> SimpleValue.NULL
  }
}
