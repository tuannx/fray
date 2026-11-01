package org.pastalab.fray.core.observers

import java.nio.file.Path
import org.pastalab.fray.core.FrayInternalError
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.command.Configuration
import org.pastalab.fray.core.randomness.StrictRecordedIntRandomness
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver
import org.pastalab.fray.runtime.TargetTerminateException

/** Identifies the application failure and its top stack frame. */
data class ReplayFailureFingerprint(
    val exceptionClass: String,
    val message: String?,
    val applicationFrame: StackTraceElement,
) {
  fun matches(error: Throwable): Boolean =
      error !is FrayInternalError &&
          error !is TargetTerminateException &&
          error.javaClass.name == exceptionClass &&
          error.message == message &&
          error.stackTrace.firstOrNull() == applicationFrame
}

/**
 * Verifies the recorded schedule prefix through the first reported application failure.
 *
 * Register the same instance as both a schedule and a test-status observer, and call
 * [requireMatched] after the runner has returned. This verifier records divergence instead of
 * throwing from a scheduling callback: callers must check the final result, because Fray may catch
 * scheduler exceptions while terminating a run. ScheduleRecorder saves its trace at the first
 * error, so scheduling during later teardown is outside that recorded prefix.
 *
 * An optional [StrictRecordedIntRandomness] checks that a legacy INT-only stream was consumed at
 * the first-error boundary. Neither this count nor the schedule prefix validates RNG call sites,
 * coroutine worker RNG, or uncaught errors Fray does not report to observers. Matching them is not
 * proof of deterministic replay or safe teardown; the caller must also validate process completion.
 */
class StrictReplayVerifier(
    expected: List<ScheduleRecording>,
    private val expectedFailure: ReplayFailureFingerprint,
) : ScheduleObserver<ThreadContext>, TestStatusObserver {
  private val expected = expected.map { it.copy(enabled = it.enabled.toList()) }
  private var started = false
  private var index = 0
  private var firstErrorObserved = false
  private var executionDone = false
  private var failure: String? = null
  private var randomness: StrictRecordedIntRandomness? = null
  private var randomnessConfig: Configuration? = null
  private var adapterRegistration: StrictReplayObserverAdapter? = null

  /** Bind the scheduler's strict stream; both observers must be registered before execution. */
  @Synchronized
  fun attachRandomness(config: Configuration) {
    if (started || randomness != null) {
      fail("Strict randomness must be attached only once before execution")
      throw IllegalStateException(failure)
    }
    val strict = config.scheduler.rand as? StrictRecordedIntRandomness
    if (strict == null) {
      fail("Replay scheduler must use strict recorded INT randomness")
      throw IllegalStateException(failure)
    }
    randomness = strict
    randomnessConfig = config
  }

  /** Bind an exact, framework-owned forwarding adapter; arbitrary observer proxies are rejected. */
  @Synchronized
  fun attachRandomnessVia(config: Configuration, adapter: StrictReplayObserverAdapter) {
    if (!adapter.delegatesTo(this)) {
      fail("Strict replay adapter must forward to this verifier")
      throw IllegalStateException(failure)
    }
    attachRandomness(config)
    adapterRegistration = adapter
  }

  @Synchronized
  override fun onExecutionStart() {
    if (started) {
      fail("Strict replay verifier cannot be reused across executions")
      return
    }
    started = true
    verifyAttachment()
  }

  @Synchronized
  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    observeStep(
        ScheduleRecording(
            scheduled.index,
            allThreads.map { it.index },
            scheduled.pendingOperation.javaClass.name,
        )
    )
  }

  /** Separated from ThreadContext to make exact trace matching independently testable. */
  @Synchronized
  internal fun observeStep(actual: ScheduleRecording) {
    if (!started || executionDone) {
      fail("Replay schedule event occurred outside the execution")
      return
    }
    if (firstErrorObserved || failure != null) return
    if (index >= expected.size) {
      failure = "Replay exceeded recorded schedule at index $index"
      return
    }
    val recorded = expected[index]
    if (recorded != actual) {
      failure = "Replay diverged at schedule index $index: expected $recorded, got $actual"
      return
    }
    index++
  }

  @Synchronized
  override fun onReportError(throwable: Throwable) {
    if (!started || executionDone) {
      fail("Replay error occurred outside the execution")
      return
    }
    if (firstErrorObserved) {
      fail("Replay reported more than one error")
      return
    }
    firstErrorObserved = true
    verifyAttachment()
    randomness?.captureAtFirstError()?.let { fail(it) }
    if (!expectedFailure.matches(throwable)) {
      fail("Replay reported an unexpected application failure: ${throwable.javaClass.name}")
    } else if (index != expected.size) {
      fail("Replay reported the expected failure after $index of ${expected.size} schedule steps")
    }
  }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {}

  @Synchronized
  override fun onExecutionDone(bugFound: Throwable?) {
    if (!started || executionDone) {
      fail("Replay execution-done event occurred outside the execution")
      return
    }
    executionDone = true
    if (!firstErrorObserved || bugFound == null || !expectedFailure.matches(bugFound)) {
      fail("Replay ended without the expected application failure")
    }
  }

  override fun saveToReportFolder(path: Path) {}

  @Synchronized
  fun requireMatched() {
    verifyAttachment()
    randomness?.violation()?.let { fail(it) }
    failure?.let { throw IllegalStateException(it) }
    check(started) { "Replay did not start" }
    check(firstErrorObserved) { "Replay did not report the expected application failure" }
    check(index == expected.size) { "Replay did not consume the recorded schedule" }
    check(executionDone) { "Replay did not reach its execution-done observer" }
  }

  private fun fail(message: String) {
    if (failure == null) failure = message
  }

  private fun verifyAttachment() {
    val config = randomnessConfig ?: return
    if (config.scheduler.rand !== randomness) fail("Replay scheduler randomness was replaced")
    val adapter = adapterRegistration
    if (adapter == null) {
      if (!config.scheduleObservers.contains(this) || !config.testStatusObservers.contains(this)) {
        fail("Strict replay verifier must be registered as both observers")
      }
    } else {
      val schedules = config.scheduleObservers
      val statuses = config.testStatusObservers
      if (
          !adapter.delegatesTo(this) ||
              schedules.count { it === adapter } != 1 ||
              statuses.count { it === adapter } != 1 ||
              schedules.any { it === this } ||
              statuses.any { it === this } ||
              schedules.count { it is StrictReplayObserverAdapter && it.delegatesTo(this) } != 1 ||
              statuses.count { it is StrictReplayObserverAdapter && it.delegatesTo(this) } != 1
      ) {
        fail("Strict replay adapter must be the unique route in both observer lists")
      }
    }
  }
}
