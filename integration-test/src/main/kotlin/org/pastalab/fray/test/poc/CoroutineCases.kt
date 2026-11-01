package org.pastalab.fray.test.poc

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small application workloads executed by CoroutinePocTest under Fray. */
object CoroutineCases {
  private fun markCompleted(name: String) {
    val key = "fray.coroutine.completed.$name"
    System.setProperty(key, ((System.getProperty(key)?.toInt() ?: 0) + 1).toString())
  }

  @JvmStatic
  fun baseline() = runBlocking {
    val results = (1..2).map { n -> async(Dispatchers.Default) { n * 2 } }
    check(results.awaitAll().sum() == 6)
    markCompleted("baseline")
  }

  @JvmStatic
  fun race() = runBlocking {
    val counter = AtomicInteger()
    val jobs =
        List(2) { launch(Dispatchers.Default) { repeat(10) { counter.set(counter.get() + 1) } } }
    jobs.forEach { it.join() }
    check(counter.get() == 20) { "lost update: expected 20, got ${counter.get()}" }
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
    check(counter.get() == 20) { "mutex counter: expected 20, got ${counter.get()}" }
    markCompleted("mutex")
  }
}

/** Run directly on a normal JVM as a control without Fray instrumentation. */
object CoroutineControl {
  @JvmStatic
  fun main(args: Array<String>) {
    CoroutineCases.baseline()
    CoroutineCases.mutex()
    println("baseline and mutex completed")
  }
}
