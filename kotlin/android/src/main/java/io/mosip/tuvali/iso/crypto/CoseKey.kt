package io.mosip.tuvali.iso.crypto

import co.nstant.`in`.cbor.model.DataItem
import io.mosip.tuvali.iso.cbor.cborMapOf

// COSE_Key labels and values (RFC 8152, IANA COSE registry)
private const val LABEL_KEY_TYPE = 1L
private const val LABEL_CURVE = -1L
private const val LABEL_X = -2L
private const val LABEL_Y = -3L
private const val KEY_TYPE_EC2 = 2L
private const val CURVE_P256 = 1L

/**
 * The public key as a COSE_Key: EC2 on P-256 with uncompressed x and y, and no optional
 * parameters besides crv, x and y (ISO 18013-5 clause 9.1.5.2).
 */
fun EphemeralDeviceKey.toCoseKey(): DataItem = cborMapOf(
  LABEL_KEY_TYPE to KEY_TYPE_EC2,
  LABEL_CURVE to CURVE_P256,
  LABEL_X to x,
  LABEL_Y to y,
)
