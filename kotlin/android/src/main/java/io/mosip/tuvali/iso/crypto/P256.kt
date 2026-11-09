package io.mosip.tuvali.iso.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters

/** NIST P-256 (secp256r1), the curve Tuvali uses for both ephemeral session keys. */
internal val P256: ECDomainParameters = CustomNamedCurves.getByName("secp256r1").let { curve ->
  ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
}

/** Size of a P-256 coordinate, private scalar or ECDH secret in bytes. */
internal const val P256_FIELD_BYTES = 32
