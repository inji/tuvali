package io.mosip.tuvali.iso.crypto

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.math.ec.FixedPointCombMultiplier

/**
 * The mdoc's ephemeral P-256 key pair for one session (EDeviceKey, ISO 18013-5 clause 9.1.1.4).
 *
 * [wipe] drops the private key at the end of the session. The JVM cannot zero the underlying
 * BigInteger, so wiping means "no longer reachable or usable", not "overwritten in memory".
 */
class EphemeralDeviceKey private constructor(privateKey: ECPrivateKeyParameters) {

  // volatile so a wipe on one thread is seen at once by a session using the key on another
  @Volatile
  private var privateKey: ECPrivateKeyParameters? = privateKey

  val publicKey: ECPublicKeyParameters =
    ECPublicKeyParameters(FixedPointCombMultiplier().multiply(P256.g, privateKey.d).normalize(), P256)

  private val xBytes: ByteArray = publicKey.q.affineXCoord.encoded
  private val yBytes: ByteArray = publicKey.q.affineYCoord.encoded

  /** Affine x coordinate, always 32 bytes big-endian (left-padded with zeros). */
  val x: ByteArray get() = xBytes.copyOf()

  /** Affine y coordinate, always 32 bytes big-endian (left-padded with zeros). */
  val y: ByteArray get() = yBytes.copyOf()

  internal fun requirePrivateKey(): ECPrivateKeyParameters =
    checkNotNull(privateKey) { "Ephemeral device key was wiped" }

  fun wipe() {
    privateKey = null
  }

  companion object {
    fun generate(random: SecureRandom = SecureRandom()): EphemeralDeviceKey {
      val generator = ECKeyPairGenerator().apply { init(ECKeyGenerationParameters(P256, random)) }
      return EphemeralDeviceKey(generator.generateKeyPair().private as ECPrivateKeyParameters)
    }

    /** Rebuilds a key from its 32-byte big-endian private scalar; used with fixed test vectors. */
    internal fun fromPrivateScalar(scalar: ByteArray): EphemeralDeviceKey {
      val d = BigInteger(1, scalar)
      require(d.signum() > 0 && d < P256.n) { "Private scalar must be in [1, n - 1]" }
      return EphemeralDeviceKey(ECPrivateKeyParameters(d, P256))
    }
  }
}
