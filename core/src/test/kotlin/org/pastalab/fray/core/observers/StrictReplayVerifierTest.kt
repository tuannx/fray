package org.pastalab.fray.core.observers

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.pastalab.fray.core.FrayInternalError
import org.pastalab.fray.runtime.LivenessException

class StrictReplayVerifierTest {
  private val frame = StackTraceElement("example.Account", "withdraw", "Account.kt", 42)
  private val expectedFailure =
      ReplayFailureFingerprint(IllegalStateException::class.java.name, "lost update", frame)
  private val first = ScheduleRecording(0, listOf(0, 1), "example.Read")
  private val second = ScheduleRecording(1, listOf(0, 1), "example.Write")

  private fun appFailure(): IllegalStateException =
      IllegalStateException("lost update").apply { stackTrace = arrayOf(frame) }

  private fun verifier(trace: List<ScheduleRecording> = listOf(first, second)) =
      StrictReplayVerifier(trace, expectedFailure).apply { onExecutionStart() }

  @Test
  fun matchesEntireRecordedPrefixAtFirstBugAndAllowsTeardownEvents() {
    val verifier = verifier()
    verifier.observeStep(first)
    verifier.observeStep(second)
    val error = appFailure()
    verifier.onReportError(error)
    // ScheduleRecorder writes its prefix at the bug, before cleanup operations.
    verifier.observeStep(ScheduleRecording(0, listOf(0), "cleanup"))
    verifier.onExecutionDone(error)
    verifier.requireMatched()
  }

  @Test
  fun snapshotsTraceAndRegisteredThreadLists() {
    val registered = mutableListOf(0, 1)
    val trace = mutableListOf(ScheduleRecording(0, registered, "example.Read"))
    val verifier = verifier(trace)
    registered.clear()
    trace.clear()
    verifier.observeStep(first)
    val error = appFailure()
    verifier.onReportError(error)
    verifier.onExecutionDone(error)
    verifier.requireMatched()
  }

  @Test
  fun rejectsTraceOverrunAndMutatedStep() {
    val truncated = verifier(listOf(first))
    truncated.observeStep(first)
    truncated.observeStep(second)
    truncated.onReportError(appFailure())
    truncated.onExecutionDone(appFailure())
    assertFailsWith<IllegalStateException> { truncated.requireMatched() }

    val mutated = verifier()
    mutated.observeStep(first.copy(operation = "example.Other"))
    mutated.onReportError(appFailure())
    mutated.onExecutionDone(appFailure())
    assertFailsWith<IllegalStateException> { mutated.requireMatched() }
  }

  @Test
  fun rejectsFirstBugBeforeAllRecordedSteps() {
    val verifier = verifier()
    verifier.observeStep(first)
    val error = appFailure()
    verifier.onReportError(error)
    verifier.onExecutionDone(error)
    assertFailsWith<IllegalStateException> { verifier.requireMatched() }
  }

  @Test
  fun rejectsWrongExceptionMessageAndApplicationFrame() {
    val wrongType = IllegalArgumentException("lost update").apply { stackTrace = arrayOf(frame) }
    val wrongMessage = IllegalStateException("wrong value").apply { stackTrace = arrayOf(frame) }
    val wrongFrame = IllegalStateException("lost update").apply { stackTrace = emptyArray() }
    val frameNotOnTop =
        IllegalStateException("lost update").apply {
          stackTrace = arrayOf(StackTraceElement("example.Helper", "run", "Helper.kt", 8), frame)
        }
    for (error in listOf(wrongType, wrongMessage, wrongFrame, frameNotOnTop)) {
      val verifier = verifier(emptyList())
      verifier.onReportError(error)
      verifier.onExecutionDone(error)
      assertFailsWith<IllegalStateException> { verifier.requireMatched() }
    }
  }

  @Test
  fun rejectsMissingBugOrMissingExecutionDone() {
    val noBug = verifier(emptyList())
    noBug.onExecutionDone(null)
    assertFailsWith<IllegalStateException> { noBug.requireMatched() }

    val noDone = verifier(emptyList())
    noDone.onReportError(appFailure())
    assertFailsWith<IllegalStateException> { noDone.requireMatched() }
  }

  @Test
  fun rejectsFrayInternalAndTerminationErrorsEvenIfFingerprintMatches() {
    for (error in listOf(FrayInternalError("internal"), LivenessException())) {
      error.stackTrace = arrayOf(frame)
      val fingerprint = ReplayFailureFingerprint(error.javaClass.name, error.message, frame)
      val verifier = StrictReplayVerifier(emptyList(), fingerprint)
      verifier.onExecutionStart()
      verifier.onReportError(error)
      verifier.onExecutionDone(error)
      assertFailsWith<IllegalStateException> { verifier.requireMatched() }
    }
  }

  @Test
  fun rejectsCallbacksOutsideExecutionOrVerifierReuse() {
    val notStarted = StrictReplayVerifier(emptyList(), expectedFailure)
    notStarted.onReportError(appFailure())
    assertFailsWith<IllegalStateException> { notStarted.requireMatched() }

    val reused = verifier(emptyList())
    val error = appFailure()
    reused.onReportError(error)
    reused.onExecutionDone(error)
    reused.onExecutionStart()
    assertFailsWith<IllegalStateException> { reused.requireMatched() }
  }
}
