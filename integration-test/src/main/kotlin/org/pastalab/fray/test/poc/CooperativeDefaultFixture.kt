package org.pastalab.fray.test.poc

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Experimental bounded fixture. No properties, host callbacks, timers, or custom dispatchers. */
object CooperativeDefaultFixture {
  @JvmStatic
  fun baseline() = runBlocking {
    val results = (1..2).map { n -> async(Dispatchers.Default) { n * 2 } }
    if (results.awaitAll().sum() != 6) throw IllegalStateException("baseline failed")
  }

  @JvmStatic
  fun mutex() = runBlocking {
    val counter = AtomicInteger()
    val mutex = Mutex()
    val jobs =
        List(2) {
          launch(Dispatchers.Default) {
            repeat(10) { mutex.withLock { counter.set(counter.get() + 1) } }
          }
        }
    jobs.forEach { it.join() }
    if (counter.get() != 20) throw IllegalStateException("mutex failed")
  }

  @JvmStatic
  fun race() = runBlocking {
    val counter = AtomicInteger()
    val jobs =
        List(2) { launch(Dispatchers.Default) { repeat(10) { counter.set(counter.get() + 1) } } }
    jobs.forEach { it.join() }
    if (counter.get() != 20) throw IllegalStateException("lost update")
  }
}
