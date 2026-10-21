package io.mosip.tuvali.testutils

import io.mosip.tuvali.iso.transport.MdocTransport
import io.mosip.tuvali.iso.transport.ReaderEvent

/**
 * An [MdocTransport] that hands out scripted reader events and records what the session sends,
 * so session tests need no Bluetooth at all.
 */
internal class ScriptedTransport(vararg incoming: ReaderEvent) : MdocTransport {
  private val incoming = ArrayDeque(incoming.toList())
  val sent = mutableListOf<ByteArray>()

  /** Set to make every send fail, e.g. because the link is already gone. */
  var failOnSend: Exception? = null

  override suspend fun open() = Unit

  override suspend fun send(message: ByteArray) {
    failOnSend?.let { throw it }
    sent += message
  }

  override suspend fun receive(): ReaderEvent = incoming.removeFirst()

  override suspend fun close() = Unit
}
