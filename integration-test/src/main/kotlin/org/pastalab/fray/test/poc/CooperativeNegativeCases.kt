package org.pastalab.fray.test.poc

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Structured but never completed; only use behind an external watchdog. */
object CooperativeSuspendedCase {
  @JvmStatic
  fun run() {
    runBlocking { awaitCancellation() }
  }
}

/** Explicitly unsupported detached work; admission should reject GlobalScope. */
object CooperativeDetachedCase {
  @OptIn(DelicateCoroutinesApi::class)
  @JvmStatic
  fun run() {
    GlobalScope.launch(Dispatchers.Default) {}
  }
}

/** Structured cancellation after the Default worker reaches a rendezvous and suspends. */
object CooperativeCancellationCase {
  @JvmStatic
  fun run() {
    runBlocking {
      val ready = Channel<Unit>()
      val worker =
          launch(Dispatchers.Default) {
            ready.send(Unit)
            awaitCancellation()
          }
      ready.receive()
      worker.cancelAndJoin()
    }
  }
}
