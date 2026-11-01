package org.pastalab.fray.core.observers

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.pastalab.fray.core.FrayInternalError
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver
import org.pastalab.fray.rmi.ThreadState
import org.pastalab.fray.runtime.Runtime
import org.pastalab.fray.runtime.TargetTerminateException

private const val MAX_STEPS = 10_000
private const val MAX_THREAD_SNAPSHOTS = 100_000
private const val MAX_TRACE_BYTES = 32 * 1024 * 1024
private const val MAX_TEXT_LENGTH = 512
const val STRICT_STATE_V1_FILE = "strict-state-v1.json"

/** Read only bounded UTF-8 JSON. Full structural validation occurs in the verifier constructor. */
object StrictStateFilesV1 {
  @JvmStatic
  fun read(path: Path): StrictStateTraceV1 {
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_TRACE_BYTES + 1) }
    require(bytes.size <= MAX_TRACE_BYTES) { "Strict state trace exceeds size limit" }
    val decoder =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    return Json.decodeFromString(decoder.decode(ByteBuffer.wrap(bytes)).toString())
  }
}

private fun fingerprint(error: Throwable): StrictFailureV1 {
  require(error !is FrayInternalError && error !is TargetTerminateException) {
    "Fray internal or termination errors are not application replay evidence"
  }
  val top =
      error.stackTrace.firstOrNull()
          ?: throw IllegalArgumentException("Application failure has no stack frame")
  val result =
      StrictFailureV1(
          error.javaClass.name,
          error.message,
          top.className,
          top.methodName,
          top.fileName,
          top.lineNumber,
      )
  validateFailure(result)
  return result
}

private fun validateFailure(failure: StrictFailureV1) {
  require(failure.exceptionClass.isNotEmpty() && failure.exceptionClass.length <= MAX_TEXT_LENGTH)
  require(failure.topClass.isNotEmpty() && failure.topClass.length <= MAX_TEXT_LENGTH)
  require(failure.topMethod.isNotEmpty() && failure.topMethod.length <= MAX_TEXT_LENGTH)
  require(failure.topFile == null || failure.topFile.length <= MAX_TEXT_LENGTH)
  require(failure.message == null || failure.message.length <= MAX_TEXT_LENGTH)
}

private fun validateStep(step: StrictScheduleStepV1) {
  require(step.threads.isNotEmpty() && step.threads.size <= StrictStateSnapshotter.MAX_THREADS)
  val indices = step.threads.map { it.index }
  require(indices == indices.distinct().sorted() && indices.all { it >= 0 })
  require(step.runnableIndices.isNotEmpty())
  require(step.runnableIndices == step.runnableIndices.distinct().sorted())
  require(step.selectedIndex in step.runnableIndices && step.runnableIndices.all { it in indices })
  val states = ThreadState.values().map { it.name }.toSet()
  step.threads.forEach {
    require(
        it.state in states &&
            it.pendingOperation.isNotEmpty() &&
            it.pendingOperation.length <= MAX_TEXT_LENGTH
    )
    require(it.ownedCoroutineWorker == (it.workerRngState != null))
  }
}

/**
 * Record a versioned scheduler-state prefix. Register this same instance in both observer lists,
 * and wrap the scheduler in [StrictStateCapturingScheduler] using the same capture. It freezes at
 * the first expected application error, when Fray saves its report; later teardown is separate.
 */
class StrictStateRecorderV1(
    private val capture: StrictStateCapture,
    private val expectation: StrictFailureExpectation,
    private val requireCoroutineWorker: Boolean = true,
) : ScheduleObserver<ThreadContext>, TestStatusObserver {
  private val snapshotter = StrictStateSnapshotter()
  private val steps = mutableListOf<StrictScheduleStepV1>()
  private var threadSnapshots = 0
  private var started = false
  private var ended = false
  private var firstError: StrictFailureV1? = null
  private var saved = false
  private var failure: String? = null

  @Synchronized
  override fun onExecutionStart() {
    if (started && !ended) {
      fail("Previous execution did not finish")
      return
    }
    try {
      capture.requireEmpty()
    } catch (e: Exception) {
      fail(e.message ?: "Incomplete scheduler capture")
    }
    steps.clear()
    threadSnapshots = 0
    snapshotter.clear()
    started = true
    ended = false
    firstError = null
    saved = false
  }

  @Synchronized
  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    Runtime.onSkipPrimitive("strict-state-v1")
    try {
      val candidates = capture.take(scheduled)
      if (!started || ended) {
        fail("Schedule event outside execution")
        return
      }
      if (firstError != null || failure != null) return
      observeSnapshot(snapshotter.snapshot(scheduled, candidates, allThreads))
    } catch (e: Exception) {
      fail(e.message ?: "Strict state snapshot failed")
    } catch (e: LinkageError) {
      fail("Strict state snapshot linkage failed: ${e.javaClass.name}")
    } finally {
      Runtime.onSkipPrimitiveDone("strict-state-v1")
    }
  }

  @Synchronized
  internal fun observeSnapshot(step: StrictScheduleStepV1) {
    if (!started || ended) {
      fail("Schedule event outside execution")
      return
    }
    if (firstError != null || failure != null) return
    try {
      validateStep(step)
    } catch (e: Exception) {
      fail("Invalid schedule snapshot")
      return
    }
    if (steps.size >= MAX_STEPS) {
      fail("Strict state trace exceeded $MAX_STEPS steps")
      return
    }
    if (threadSnapshots + step.threads.size > MAX_THREAD_SNAPSHOTS) {
      fail("Strict state trace exceeded $MAX_THREAD_SNAPSHOTS thread snapshots")
      return
    }
    steps.add(
        step.copy(runnableIndices = step.runnableIndices.toList(), threads = step.threads.toList())
    )
    threadSnapshots += step.threads.size
  }

  @Synchronized
  override fun onReportError(throwable: Throwable) {
    if (!started || ended || firstError != null) {
      fail("Unexpected error callback")
      return
    }
    try {
      capture.requireEmpty()
      val found = fingerprint(throwable)
      if (
          found.exceptionClass != expectation.exceptionClass ||
              found.topClass != expectation.topClass ||
              found.topMethod != expectation.topMethod ||
              found.message?.startsWith(expectation.messagePrefix) != true
      ) {
        fail("Unexpected application error")
        return
      }
      if (steps.isEmpty()) {
        fail("Empty schedule is not race evidence")
        return
      }
      if (
          requireCoroutineWorker &&
              steps.none { step -> step.threads.any { it.ownedCoroutineWorker } }
      ) {
        fail("No owned coroutine worker was observed")
        return
      }
      firstError = found
    } catch (e: Exception) {
      fail(e.message ?: "Failed to capture application error")
    }
  }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {}

  @Synchronized
  override fun onExecutionDone(bugFound: Throwable?) {
    if (!started || ended) {
      fail("Unexpected execution-done callback")
      return
    }
    ended = true
    try {
      capture.requireEmpty()
      if (firstError != null && (bugFound == null || fingerprint(bugFound) != firstError)) {
        fail("Execution ended with a different error")
      }
    } catch (e: Exception) {
      fail(e.message ?: "Failed to verify execution end")
    }
    snapshotter.clear()
  }

  @Synchronized
  override fun saveToReportFolder(path: Path) {
    Runtime.onSkipPrimitive("strict-state-v1")
    try {
      val target = path.resolve(STRICT_STATE_V1_FILE)
      Files.deleteIfExists(target)
      val error = firstError
      if (failure == null && error != null) {
        val encoded =
            Json.encodeToString(StrictStateTraceV1(1, error, steps.toList()))
                .toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_TRACE_BYTES) { "Strict state trace exceeds size limit" }
        Files.write(target, encoded)
        saved = true
      } else {
        fail("Strict state trace was not saved")
      }
    } catch (e: Exception) {
      fail(e.message ?: "Failed to save strict state trace")
    } finally {
      Runtime.onSkipPrimitiveDone("strict-state-v1")
    }
  }

  @Synchronized
  fun requireRecorded() {
    failure?.let { throw IllegalStateException(it) }
    check(started && ended && firstError != null && saved) { "Strict state trace is incomplete" }
    capture.requireEmpty()
  }

  private fun fail(message: String) {
    if (failure == null) failure = message
  }
}

/** Verifies every versioned state snapshot up to the exact recorded application failure. */
class StrictStateReplayVerifierV1(
    private val capture: StrictStateCapture,
    source: StrictStateTraceV1,
    requireCoroutineWorker: Boolean = true,
) : ScheduleObserver<ThreadContext>, TestStatusObserver {
  private val trace =
      StrictStateTraceV1(
          source.version,
          source.failure.copy(),
          source.steps.map {
            it.copy(runnableIndices = it.runnableIndices.toList(), threads = it.threads.toList())
          },
      )
  private val snapshotter = StrictStateSnapshotter()
  private var index = 0
  private var started = false
  private var ended = false
  private var firstError = false
  private var failure: String? = null

  init {
    require(trace.version == 1) { "Unsupported strict state trace version" }
    require(trace.steps.isNotEmpty() && trace.steps.size <= MAX_STEPS)
    validateFailure(trace.failure)
    require(trace.steps.sumOf { it.threads.size.toLong() } <= MAX_THREAD_SNAPSHOTS)
    trace.steps.forEach(::validateStep)
    require(
        !requireCoroutineWorker ||
            trace.steps.any { s -> s.threads.any { it.ownedCoroutineWorker } }
    )
  }

  @Synchronized
  override fun onExecutionStart() {
    if (started) {
      fail("Strict state verifier cannot be reused")
      return
    }
    started = true
    try {
      capture.requireEmpty()
    } catch (e: Exception) {
      fail(e.message ?: "Incomplete scheduler capture")
    }
  }

  @Synchronized
  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    Runtime.onSkipPrimitive("strict-state-v1")
    try {
      val candidates = capture.take(scheduled)
      if (!started || ended) {
        fail("Schedule event outside execution")
        return
      }
      if (firstError || failure != null) return
      observeSnapshot(snapshotter.snapshot(scheduled, candidates, allThreads))
    } catch (e: Exception) {
      fail(e.message ?: "Strict state snapshot failed")
    } catch (e: LinkageError) {
      fail("Strict state snapshot linkage failed: ${e.javaClass.name}")
    } finally {
      Runtime.onSkipPrimitiveDone("strict-state-v1")
    }
  }

  @Synchronized
  internal fun observeSnapshot(step: StrictScheduleStepV1) {
    if (!started || ended) {
      fail("Schedule event outside execution")
      return
    }
    if (firstError || failure != null) return
    try {
      validateStep(step)
    } catch (e: Exception) {
      fail("Invalid schedule snapshot")
      return
    }
    if (index >= trace.steps.size) {
      fail("Replay exceeded strict state trace at $index")
      return
    }
    if (trace.steps[index] != step) {
      fail("Replay state diverged at $index")
      return
    }
    index++
  }

  @Synchronized
  override fun onReportError(throwable: Throwable) {
    if (!started || ended || firstError) {
      fail("Unexpected error callback")
      return
    }
    firstError = true
    try {
      capture.requireEmpty()
      if (fingerprint(throwable) != trace.failure)
          fail("Replay reported a different application error")
      if (index != trace.steps.size)
          fail("Replay consumed $index of ${trace.steps.size} state steps")
    } catch (e: Exception) {
      fail(e.message ?: "Failed to verify application error")
    }
  }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {}

  @Synchronized
  override fun onExecutionDone(bugFound: Throwable?) {
    if (!started || ended) {
      fail("Unexpected execution-done callback")
      return
    }
    ended = true
    try {
      capture.requireEmpty()
      if (!firstError || bugFound == null || fingerprint(bugFound) != trace.failure) {
        fail("Execution ended without the recorded application failure")
      }
    } catch (e: Exception) {
      fail(e.message ?: "Failed to verify execution end")
    }
    snapshotter.clear()
  }

  override fun saveToReportFolder(path: Path) {}

  @Synchronized
  fun requireMatched() {
    failure?.let { throw IllegalStateException(it) }
    check(started && ended && firstError && index == trace.steps.size) {
      "Strict state replay is incomplete"
    }
    capture.requireEmpty()
  }

  private fun fail(message: String) {
    if (failure == null) failure = message
  }
}
