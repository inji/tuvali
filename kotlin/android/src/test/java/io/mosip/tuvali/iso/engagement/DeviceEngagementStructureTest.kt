package io.mosip.tuvali.iso.engagement

import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.SimpleValue
import io.mosip.tuvali.iso.cbor.decodeCbor
import io.mosip.tuvali.iso.cbor.untag24
import io.mosip.tuvali.testutils.asBytes
import io.mosip.tuvali.testutils.asLong
import io.mosip.tuvali.testutils.asText
import io.mosip.tuvali.testutils.encodeCanonicalCbor
import io.mosip.tuvali.testutils.integerKeys
import io.mosip.tuvali.testutils.items
import io.mosip.tuvali.testutils.toHex
import io.mosip.tuvali.testutils.valueAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structure checks of the GOV.UK ISO 18013-6 mdoc device engagement cases (mDL_MS_DE_*),
 * applied to what [DeviceEngagement.create] produces, plus the BleOptions rules of
 * ISO 18013-5 clause 8.3.3.1.1.2 for an mdoc that only supports central client mode.
 */
class DeviceEngagementStructureTest {

  private val engagement = DeviceEngagement.create()
  private val decoded = decodeCbor(engagement.bytes)

  @Test
  fun `engagement bytes are canonical CBOR with sorted map keys (DE_01)`() {
    val canonical = encodeCanonicalCbor(decoded)

    assertEquals(canonical.toHex(), engagement.bytes.toHex())
  }

  @Test
  fun `engagement holds only version, security and device retrieval methods (DE_02, DE_03)`() {
    val keys = decoded.integerKeys()

    assertEquals(listOf(0L, 1L, 2L), keys)
  }

  @Test
  fun `engagement version is 1_0 (DE_04)`() {
    val version = decoded.valueAt(0).asText()

    assertEquals("1.0", version)
  }

  @Test
  fun `security is cipher suite 1 followed by tag 24 EDeviceKeyBytes (DE_05, DE_06, DE_07)`() {
    val security = decoded.valueAt(1).items()

    assertEquals(2, security.size)
    assertEquals(1L, security[0].asLong())
    assertTrue(security[1] is ByteString)
    assertEquals(24L, security[1].tag?.value)
  }

  @Test
  fun `EDeviceKey inside the engagement is canonical CBOR (DE_08)`() {
    val eDeviceKey = untag24(decoded.valueAt(1).items()[1])

    val canonical = encodeCanonicalCbor(decodeCbor(eDeviceKey))

    assertEquals(canonical.toHex(), eDeviceKey.toHex())
  }

  @Test
  fun `device retrieval methods hold exactly one method, BLE version 1 with options (DE_11, DE_12)`() {
    val methods = decoded.valueAt(2).items()

    assertEquals(1, methods.size)
    val bleMethod = methods[0].items()
    assertEquals(3, bleMethod.size)
    // Table 7: type 2 is BLE, version 1
    assertEquals(2L, bleMethod[0].asLong())
    assertEquals(1L, bleMethod[1].asLong())
  }

  @Test
  fun `BLE options offer central client mode only, with a 16-byte client UUID and no server fields`() {
    val bleOptions = decoded.valueAt(2).items()[0].items()[2]

    // 0: peripheral server mode, 1: central client mode, 11: client UUID; 10 and 20 are server-only
    assertEquals(listOf(0L, 1L, 11L), bleOptions.integerKeys())
    assertEquals(SimpleValue.FALSE, bleOptions.valueAt(0))
    assertEquals(SimpleValue.TRUE, bleOptions.valueAt(1))
    assertEquals(16, bleOptions.valueAt(11).asBytes().size)
  }
}
