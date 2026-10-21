package io.mosip.tuvali.iso.crypto

import co.nstant.`in`.cbor.model.DataItem
import io.mosip.tuvali.iso.cbor.cborMapOf

// COSE_Key labels and values (RFC 8152, IANA COSE registry)
internal const val COSE_LABEL_KEY_TYPE = 1L
internal const val COSE_LABEL_CURVE = -1L
internal const val COSE_LABEL_X = -2L
internal const val COSE_LABEL_Y = -3L
internal const val COSE_KEY_TYPE_EC2 = 2L
internal const val COSE_CURVE_P256 = 1L

/**
 * The public key as a COSE_Key: EC2 on P-256 with uncompressed x and y, and no optional
 * parameters besides crv, x and y (ISO 18013-5 clause 9.1.5.2).
 */
internal fun EphemeralDeviceKey.toCoseKey(): DataItem = cborMapOf(
  COSE_LABEL_KEY_TYPE to COSE_KEY_TYPE_EC2,
  COSE_LABEL_CURVE to COSE_CURVE_P256,
  COSE_LABEL_X to x,
  COSE_LABEL_Y to y,
)
