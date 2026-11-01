package org.pastalab.fray.poc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small application workloads executed by CoroutinePocTest under Fray. */
object CoroutineCases {
  private class Counter(var value: Int = 0)

  @JvmStatic
  fun baseline() = runBlocking {
    val results = (1..2).map { n -> async(Dispatchers.Default) { n * 2 } }
    check(results.awaitAll().sum() == 6)
  }

  @JvmStatic
  fun race() = runBlocking {
    val counter = Counter()
    val jobs = List(2) { launch(Dispatchers.Default) { repeat(10) { counter.value++ } } }
    jobs.forEach { it.join() }
    check(counter.value == 20) { "lost update: expected 20, got ${counter.value}" }
  }

  @JvmStatic
  fun mutex() = runBlocking {
    val counter = Counter()
    val mutex = Mutex()
    val jobs =
        List(2) {
          launch(Dispatchers.Default) { repeat(10) { mutex.withLock { counter.value++ } } }
        }
    jobs.forEach { it.join() }
    check(counter.value == 20) { "mutex counter: expected 20, got ${counter.value}" }
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
