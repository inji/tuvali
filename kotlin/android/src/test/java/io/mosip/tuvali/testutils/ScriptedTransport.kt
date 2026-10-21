package io.mosip.tuvali.testutils

import io.mosip.tuvali.iso.transport.MdocTransport
import io.mosip.tuvali.iso.transport.ReaderEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * An [MdocTransport] that hands out scripted reader events and records what the session sends,
 * so session and presenter tests need no Bluetooth at all. [receive] waits while nothing is
 * scripted, like a real reader that stays silent.
 */
internal class ScriptedTransport(vararg incoming: ReaderEvent) : MdocTransport {
  private val incoming = Channel<ReaderEvent>(Channel.UNLIMITED)
    .also { channel -> incoming.forEach { event -> channel.trySend(event) } }
  val sent = mutableListOf<ByteArray>()

  /** Set to make every send fail, e.g. because the link is already gone. */
  var failOnSend: Exception? = null

  /** Set to make [open] fail, e.g. Bluetooth switched off while scanning. */
  var failOnOpen: Exception? = null

  /** Set to make [open] wait until [readerFound] completes, like a scan that finds nobody yet. */
  var waitForReader = false
  val readerFound = CompletableDeferred<Unit>()

  var opened = false
    private set
  var closed = false
    private set

  fun queue(event: ReaderEvent) {
    incoming.trySend(event)
  }

  /** The link drops: whatever is queued is still delivered, then [receive] throws [failure]. */
  fun dropLink(failure: Exception) {
    incoming.close(failure)
  }

  override suspend fun open() {
    failOnOpen?.let { throw it }
    if (waitForReader) readerFound.await()
    opened = true
  }

  override suspend fun send(message: ByteArray) {
    failOnSend?.let { throw it }
    sent += message
  }

  override suspend fun receive(): ReaderEvent = incoming.receive()

  override suspend fun close() {
    closed = true
  }
}
