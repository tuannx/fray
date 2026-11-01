package org.pastalab.fray.core.observers

import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlinx.serialization.Serializable
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.randomness.Randomness
import org.pastalab.fray.core.scheduler.Scheduler
import org.pastalab.fray.rmi.ThreadState
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners

/** Experiment-only evidence. Thread states and operation classes do not identify resources. */
@Serializable
data class StrictThreadStateV1(
    val index: Int,
    val state: String,
    val pendingOperation: String,
    val ownedCoroutineWorker: Boolean,
    val workerRngState: Int?,
)

@Serializable
data class StrictScheduleStepV1(
    val selectedIndex: Int,
    val runnableIndices: List<Int>,
    val threads: List<StrictThreadStateV1>,
)

@Serializable
data class StrictFailureV1(
    val exceptionClass: String,
    val message: String?,
    val topClass: String,
    val topMethod: String,
    val topFile: String?,
    val topLine: Int,
)

@Serializable
data class StrictStateTraceV1(
    val version: Int,
    val failure: StrictFailureV1,
    val steps: List<StrictScheduleStepV1>,
)

/** Explicit expected application site prevents scheduler errors from being recorded as a race. */
data class StrictFailureExpectation(
    val exceptionClass: String,
    val topClass: String,
    val topMethod: String,
    val messagePrefix: String,
) {
  init {
    require(exceptionClass.isNotEmpty() && topClass.isNotEmpty() && topMethod.isNotEmpty())
    require(messagePrefix.isNotEmpty())
  }
}

/** Single-slot handoff from the scheduler's actual candidate list to the schedule observer. */
class StrictStateCapture {
  private var pending: Pair<Int, List<Int>>? = null

  @Synchronized
  internal fun record(candidates: List<ThreadContext>, selected: ThreadContext) {
    check(pending == null) { "Previous scheduler decision was not observed" }
    check(candidates.isNotEmpty() && candidates.any { it === selected }) {
      "Selected thread is not an actual scheduler candidate"
    }
    val indices = candidates.map { it.index }.sorted()
    check(indices.distinct().size == indices.size) { "Duplicate candidate thread index" }
    pending = selected.index to indices
  }

  @Synchronized
  internal fun take(selected: ThreadContext): List<Int> {
    val decision = pending ?: error("Missing scheduler candidate snapshot")
    pending = null
    check(decision.first == selected.index) { "Scheduler and observer selected different threads" }
    return decision.second
  }

  @Synchronized
  internal fun requireEmpty() {
    check(pending == null) { "Unconsumed scheduler decision" }
  }
}

/** Wrap both discovery and replay schedulers; it does not add or consume random draws. */
class StrictStateCapturingScheduler(
    private val delegate: Scheduler,
    private val capture: StrictStateCapture,
) : Scheduler(delegate.rand) {
  override fun scheduleNextOperation(
      threads: List<ThreadContext>,
      allThreads: Collection<ThreadContext>,
  ): ThreadContext {
    val originalCandidates = threads.toList()
    val selected = delegate.scheduleNextOperation(threads, allThreads)
    check(
        threads.size == originalCandidates.size &&
            threads.indices.all { threads[it] === originalCandidates[it] }
    ) {
      "Scheduler mutated its candidate list"
    }
    capture.record(originalCandidates, selected)
    return selected
  }

  override fun nextIteration(randomness: Randomness): Scheduler =
      StrictStateCapturingScheduler(delegate.nextIteration(randomness), capture)
}

/**
 * Pinned 1.10.1 layout only. Unknown coroutine thread types/owners and reflection failures reject.
 */
internal class StrictStateSnapshotter {
  private val seenOwners = IdentityHashMap<ClassLoader, Boolean>()

  fun snapshot(
      selected: ThreadContext,
      candidates: List<Int>,
      allThreads: Collection<ThreadContext>,
  ): StrictScheduleStepV1 {
    require(allThreads.size <= MAX_THREADS) { "Too many threads for strict state trace" }
    val threads =
        allThreads
            .map { context ->
              val thread = context.thread
              val name = thread.javaClass.name
              val isWorker = name == WORKER_CLASS
              var rng: Int? = null
              if (isWorker) {
                val loader =
                    thread.javaClass.classLoader ?: error("Coroutine worker has no owner loader")
                if (CoroutineInstrumentationOwners.owns(loader)) {
                  seenOwners[loader] = true
                } else {
                  check(
                      seenOwners.containsKey(loader) &&
                          context.state == ThreadState.Completed &&
                          !thread.isAlive
                  ) {
                    "Unknown or still-live coroutine worker owner"
                  }
                }
                val field = thread.javaClass.getDeclaredField("rngState")
                check(
                    field.type == Int::class.javaPrimitiveType &&
                        !Modifier.isStatic(field.modifiers)
                ) {
                  "Unsupported coroutine worker RNG field"
                }
                check(field.trySetAccessible()) { "Coroutine worker RNG is inaccessible" }
                rng = field.getInt(thread)
              } else {
                check(!name.startsWith("kotlinx.coroutines.")) {
                  "Unknown coroutine thread type: $name"
                }
              }
              StrictThreadStateV1(
                  context.index,
                  context.state.name,
                  context.pendingOperation.javaClass.name,
                  isWorker,
                  rng,
              )
            }
            .sortedBy { it.index }
    require(threads.map { it.index }.distinct().size == threads.size) { "Duplicate thread index" }
    val indices = threads.map { it.index }.toSet()
    require(
        candidates.isNotEmpty() && candidates.all { it in indices } && selected.index in candidates
    ) {
      "Invalid scheduler candidates"
    }
    return StrictScheduleStepV1(selected.index, candidates, threads)
  }

  fun clear() = seenOwners.clear()

  companion object {
    const val MAX_THREADS = 1024
    const val WORKER_CLASS = "kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker"
  }
}
