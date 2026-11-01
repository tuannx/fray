package org.pastalab.fray.test.poc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Disposable-process-only experimental workloads, not a public support claim. */
object IsolatedCoroutineWorkloads {
  @JvmStatic fun baseline() = CoroutineCases.baseline()

  @JvmStatic fun mutex() = CoroutineCases.mutex()

  @JvmStatic fun race() = CoroutineCases.race()

  @JvmStatic
  fun cancellation() = runBlocking {
    val ready = Channel<Unit>()
    val job =
        launch(Dispatchers.Default) {
          ready.send(Unit)
          awaitCancellation()
        }
    ready.receive()
    job.cancelAndJoin()
    val key = "fray.experiment.completed.cancellation"
    System.setProperty(key, ((System.getProperty(key)?.toInt() ?: 0) + 1).toString())
  }

  @OptIn(DelicateCoroutinesApi::class)
  @JvmStatic
  fun detachedSuspended() = runBlocking {
    GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
      awaitCancellation()
    }
    System.setProperty("fray.experiment.completed.detachedSuspended", "true")
  }

  @OptIn(DelicateCoroutinesApi::class)
  @JvmStatic
  fun detachedCompleted() = runBlocking {
    GlobalScope.launch(Dispatchers.Default) {}.join()
    System.setProperty("fray.experiment.completed.detachedCompleted", "true")
  }

  @OptIn(DelicateCoroutinesApi::class)
  @JvmStatic
  fun detachedLazy() = runBlocking {
    GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {}
    System.setProperty("fray.experiment.completed.detachedLazy", "true")
  }
}

/** Runs with the test process's parent loader, not an iteration-private coroutine runtime. */
object HostCoroutineProbe {
  @JvmStatic
  fun run() = runBlocking {
    val result = async(Dispatchers.Default) { 41 }.await()
    check(result == 41)
  }
}
