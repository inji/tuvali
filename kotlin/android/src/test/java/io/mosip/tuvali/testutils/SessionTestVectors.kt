package io.mosip.tuvali.testutils

import org.json.JSONObject

/**
 * ISO 18013-5 session vectors in src/test/resources/iso/session-vectors.json.
 * They are derived from fixed keys with cbor2 and the Python cryptography package,
 * independent of Tuvali, so the Kotlin code can be checked byte for byte.
 */
object SessionTestVectors {
  val session: JSONObject by lazy {
    val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("iso/session-vectors.json")) {
      "iso/session-vectors.json not found on the test classpath"
    }
    JSONObject(stream.bufferedReader().use { it.readText() })
  }
}
