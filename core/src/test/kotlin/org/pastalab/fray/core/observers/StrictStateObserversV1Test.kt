package org.pastalab.fray.core.observers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StrictStateObserversV1Test {
  private val frame = StackTraceElement("example.Account", "withdraw", "Account.kt", 42)
  private val expectation =
      StrictFailureExpectation(
          "java.lang.IllegalStateException",
          "example.Account",
          "withdraw",
          "lost update",
      )
  private val app = StrictThreadStateV1(0, "Runnable", "example.Read", false, null)
  private val worker = StrictThreadStateV1(1, "Runnable", "example.Write", true, 42)
  private val step = StrictScheduleStepV1(1, listOf(0, 1), listOf(app, worker))

  private fun appFailure(message: String = "lost update: 1") =
      IllegalStateException(message).apply { stackTrace = arrayOf(frame) }

  private fun recordedTrace(): StrictStateTraceV1 {
    val capture = StrictStateCapture()
    val recorder = StrictStateRecorderV1(capture, expectation)
    recorder.onExecutionStart()
    val candidates = mutableListOf(0, 1)
    val threads = mutableListOf(app, worker)
    recorder.observeSnapshot(StrictScheduleStepV1(1, candidates, threads))
    candidates.clear()
    threads.clear()
    val error = appFailure()
    recorder.onReportError(error)
    // The first-error boundary freezes the recorded prefix; cleanup is not part of this trace.
    recorder.observeSnapshot(step.copy(selectedIndex = 0))
    val directory = Files.createTempDirectory("strict-state-v1")
    recorder.saveToReportFolder(directory)
    recorder.onExecutionDone(error)
    recorder.requireRecorded()
    val path = directory.resolve(STRICT_STATE_V1_FILE)
    assertTrue(Files.isRegularFile(path))
    return StrictStateFilesV1.read(path)
  }

  private fun verifier(trace: StrictStateTraceV1 = recordedTrace()) =
      StrictStateReplayVerifierV1(StrictStateCapture(), trace).apply { onExecutionStart() }

  private fun finish(verifier: StrictStateReplayVerifierV1, error: Throwable = appFailure()) {
    verifier.onReportError(error)
    verifier.onExecutionDone(error)
  }

  @Test
  fun recordsAndMatchesExactStatePrefixAndApplicationError() {
    val trace = recordedTrace()
    assertEquals(1, trace.version)
    assertEquals(listOf(step), trace.steps)
    val verifier = verifier(trace)
    verifier.observeSnapshot(step)
    val error = appFailure()
    verifier.onReportError(error)
    verifier.observeSnapshot(step.copy(selectedIndex = 0))
    verifier.onExecutionDone(error)
    verifier.requireMatched()
  }

  @Test
  fun rejectsChangedRunnableStateSelectedThreadAndWorkerRng() {
    val variants =
        listOf(
            step.copy(runnableIndices = listOf(1)),
            step.copy(selectedIndex = 0),
            step.copy(threads = listOf(app.copy(state = "Blocked"), worker)),
            step.copy(threads = listOf(app, worker.copy(workerRngState = 43))),
        )
    for (actual in variants) {
      val verifier = verifier()
      verifier.observeSnapshot(actual)
      finish(verifier)
      assertFailsWith<IllegalStateException> { verifier.requireMatched() }
    }
  }

  @Test
  fun rejectsMissingExtraOrDifferentFailure() {
    val missing = verifier()
    finish(missing)
    assertFailsWith<IllegalStateException> { missing.requireMatched() }

    val extra = verifier()
    extra.observeSnapshot(step)
    extra.observeSnapshot(step)
    finish(extra)
    assertFailsWith<IllegalStateException> { extra.requireMatched() }

    val wrongError = verifier()
    wrongError.observeSnapshot(step)
    finish(wrongError, appFailure("lost update: 2"))
    assertFailsWith<IllegalStateException> { wrongError.requireMatched() }
  }

  @Test
  fun rejectsMutatedTruncatedAndAppendedStateArtifacts() {
    val trace = recordedTrace()
    // A structurally valid alteration must fail at the first differing observation.
    val mutated =
        verifier(
            trace.copy(
                steps = listOf(step.copy(threads = listOf(app, worker.copy(workerRngState = 43))))
            )
        )
    mutated.observeSnapshot(step)
    finish(mutated)
    assertFailsWith<IllegalStateException> { mutated.requireMatched() }

    // An empty prefix is not an admissible trace, even before replay starts.
    assertFailsWith<IllegalArgumentException> {
      StrictStateReplayVerifierV1(StrictStateCapture(), trace.copy(steps = emptyList()))
    }

    // A nonempty truncated artifact is structurally valid but must reject an extra real step.
    val full = trace.copy(steps = listOf(step, step))
    val truncated = verifier(full.copy(steps = full.steps.dropLast(1)))
    truncated.observeSnapshot(step)
    truncated.observeSnapshot(step)
    finish(truncated)
    assertFailsWith<IllegalStateException> { truncated.requireMatched() }

    // A forged extra recorded decision must not be accepted at the real failure boundary.
    val appended = verifier(trace.copy(steps = listOf(step, step)))
    appended.observeSnapshot(step)
    finish(appended)
    assertFailsWith<IllegalStateException> { appended.requireMatched() }
  }

  @Test
  fun rejectsUnknownVersionMalformedSnapshotsAndMissingWorker() {
    val trace = recordedTrace()
    assertFailsWith<IllegalArgumentException> {
      StrictStateReplayVerifierV1(StrictStateCapture(), trace.copy(version = 2))
    }
    assertFailsWith<IllegalArgumentException> {
      StrictStateReplayVerifierV1(
          StrictStateCapture(),
          trace.copy(steps = listOf(step.copy(runnableIndices = listOf(1, 0)))),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      StrictStateReplayVerifierV1(
          StrictStateCapture(),
          trace.copy(
              steps = listOf(step.copy(threads = listOf(app, worker.copy(workerRngState = null))))
          ),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      StrictStateReplayVerifierV1(
          StrictStateCapture(),
          trace.copy(steps = listOf(StrictScheduleStepV1(0, listOf(0), listOf(app)))),
      )
    }
  }

  @Test
  fun rejectsMissingCompletionUnexpectedErrorAndMalformedUtf8() {
    val verifier = verifier()
    verifier.observeSnapshot(step)
    verifier.onReportError(appFailure())
    assertFailsWith<IllegalStateException> { verifier.requireMatched() }

    val recorder = StrictStateRecorderV1(StrictStateCapture(), expectation)
    recorder.onExecutionStart()
    recorder.observeSnapshot(step)
    recorder.onReportError(IllegalStateException("unexpected"))
    val directory = Files.createTempDirectory("strict-state-v1-bad")
    recorder.saveToReportFolder(directory)
    recorder.onExecutionDone(null)
    assertFailsWith<IllegalStateException> { recorder.requireRecorded() }
    assertTrue(!Files.exists(directory.resolve(STRICT_STATE_V1_FILE)))

    val badUtf8 = directory.resolve("bad.json")
    Files.write(badUtf8, byteArrayOf(0xc3.toByte(), 0x28))
    assertFailsWith<Exception> { StrictStateFilesV1.read(badUtf8) }
  }
}
