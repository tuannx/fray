package org.pastalab.fray.core.observers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.pastalab.fray.core.RunContext
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.command.Configuration
import org.pastalab.fray.core.command.ExecutionInfo
import org.pastalab.fray.core.command.LambdaExecutor
import org.pastalab.fray.core.command.NetworkDelegateType
import org.pastalab.fray.core.command.SystemTimeDelegateType
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.ControlledRandomProvider
import org.pastalab.fray.core.randomness.StrictRecordedIntRandomness
import org.pastalab.fray.core.scheduler.RandomScheduler

class StrictReplayRandomnessTest {
  private val frame = StackTraceElement("example.Account", "withdraw", "Account.kt", 42)
  private val fingerprint =
      ReplayFailureFingerprint(IllegalStateException::class.java.name, "lost update", frame)

  private fun appFailure() =
      IllegalStateException("lost update").apply { stackTrace = arrayOf(frame) }

  private fun config(strict: StrictRecordedIntRandomness) =
      Configuration(
          ExecutionInfo(LambdaExecutor({}), false, false, -1),
          Files.createTempDirectory("fray-strict-random"),
          1,
          30,
          RandomScheduler(strict),
          ControlledRandomProvider(),
          fullSchedule = false,
          exploreMode = false,
          noExitWhenBugFound = true,
          isReplay = true,
          noFray = false,
          dummyRun = false,
          networkDelegateType = NetworkDelegateType.NONE,
          systemTimeDelegateType = SystemTimeDelegateType.NONE,
          virtualTimeDelta = 100_000L,
          ignoreTimedBlock = true,
          sleepAsYield = false,
          resetClassLoader = true,
          redirectStdout = false,
          abortThreadExecutionAfterMainExit = false,
          resolveRacingOperationStackTraceHash = false,
          timelineCoverageType = TimelineCoverageType.NONE,
      )

  private fun strict(vararg values: Int) =
      StrictRecordedIntRandomness.fromRecorded(ControlledRandom(values.toMutableList()))

  private fun attach(config: Configuration): StrictReplayVerifier {
    val verifier = StrictReplayVerifier(emptyList(), fingerprint)
    config.scheduleObservers.add(verifier)
    config.testStatusObservers.add(verifier)
    verifier.attachRandomness(config)
    verifier.onExecutionStart()
    return verifier
  }

  private fun reportAndFinish(verifier: StrictReplayVerifier) {
    val error = appFailure()
    verifier.onReportError(error)
    verifier.onExecutionDone(error)
  }

  @Test
  fun checksExactIntConsumptionAtFirstError() {
    val strict = strict(4, 8)
    val verifier = attach(config(strict))
    assertEquals(4, strict.nextInt())
    assertEquals(8, strict.nextInt())
    reportAndFinish(verifier)
    verifier.requireMatched()
  }

  @Test
  fun caughtOverrunAndUnderrunRemainFailures() {
    val short = strict(4)
    val overrun = attach(config(short))
    assertEquals(4, short.nextInt())
    assertFailsWith<IllegalStateException> { short.nextInt() }
    reportAndFinish(overrun)
    assertFailsWith<IllegalStateException> { overrun.requireMatched() }

    val long = strict(4, 8)
    val underrun = attach(config(long))
    assertEquals(4, long.nextInt())
    reportAndFinish(underrun)
    assertFailsWith<IllegalStateException> { long.nextInt() }
    assertFailsWith<IllegalStateException> { underrun.requireMatched() }
  }

  @Test
  fun caughtDoubleOrPostBoundaryDrawCannotBecomeSuccess() {
    val stream = strict()
    val verifier = attach(config(stream))
    assertFailsWith<IllegalStateException> { stream.nextDouble() }
    reportAndFinish(verifier)
    assertFailsWith<IllegalStateException> { verifier.requireMatched() }

    val after = strict()
    val postBoundary = attach(config(after))
    reportAndFinish(postBoundary)
    assertFailsWith<IllegalStateException> { after.nextInt() }
    assertFailsWith<IllegalStateException> { postBoundary.requireMatched() }
  }

  @Test
  fun requiresTheBoundSchedulerAndBothObserverRegistrations() {
    val stream = strict()
    val configuration = config(stream)
    val verifier = attach(configuration)
    configuration.scheduler = RandomScheduler()
    reportAndFinish(verifier)
    assertFailsWith<IllegalStateException> { verifier.requireMatched() }

    val otherConfig = config(strict())
    val missingScheduleObserver = StrictReplayVerifier(emptyList(), fingerprint)
    otherConfig.testStatusObservers.add(missingScheduleObserver)
    missingScheduleObserver.attachRandomness(otherConfig)
    missingScheduleObserver.onExecutionStart()
    reportAndFinish(missingScheduleObserver)
    assertFailsWith<IllegalStateException> { missingScheduleObserver.requireMatched() }

    val wrongStream = config(strict())
    wrongStream.scheduler = RandomScheduler()
    val wrong = StrictReplayVerifier(emptyList(), fingerprint)
    assertFailsWith<IllegalStateException> { wrong.attachRandomness(wrongStream) }
    assertFailsWith<IllegalStateException> { wrong.requireMatched() }
  }

  @Test
  fun exactAdapterForwardsScheduleFailureAndCompletionWithStrictRandomness() {
    // Use an independent context solely to create an actual schedulable observer argument.
    val context = RunContext(config(strict(9)))
    try {
      val thread = ThreadContext(Thread(), 0, context, 0)
      val stream = strict(4)
      val configuration = config(stream)
      val expected = ScheduleRecording(0, listOf(0), thread.pendingOperation.javaClass.name)
      val verifier = StrictReplayVerifier(listOf(expected), fingerprint)
      val adapter = StrictReplayObserverAdapter(verifier)
      // Match the experiment: bind before registration, verify membership at execution start.
      verifier.attachRandomnessVia(configuration, adapter)
      configuration.scheduleObservers.add(adapter)
      configuration.testStatusObservers.add(adapter)
      adapter.onExecutionStart()
      assertEquals(4, stream.nextInt())
      adapter.onNewSchedule(listOf(thread), thread)
      val error = appFailure()
      adapter.onReportError(error)
      adapter.onExecutionDone(error)
      verifier.requireMatched()
    } finally {
      context.shutDown()
    }
  }

  @Test
  fun adapterRejectsWrongOrMissingDelegateAndIncompleteCallbacks() {
    for (adapterDelegate in
        listOf<StrictReplayVerifier?>(null, StrictReplayVerifier(emptyList(), fingerprint))) {
      val verifier = StrictReplayVerifier(emptyList(), fingerprint)
      assertFailsWith<IllegalStateException> {
        verifier.attachRandomnessVia(config(strict()), StrictReplayObserverAdapter(adapterDelegate))
      }
      assertFailsWith<IllegalStateException> { verifier.requireMatched() }
    }

    val configuration = config(strict())
    val verifier = StrictReplayVerifier(emptyList(), fingerprint)
    val adapter = StrictReplayObserverAdapter(verifier)
    configuration.scheduleObservers.add(adapter)
    configuration.testStatusObservers.add(adapter)
    verifier.attachRandomnessVia(configuration, adapter)
    adapter.onExecutionStart()
    adapter.onExecutionDone(null)
    assertFailsWith<IllegalStateException> { verifier.requireMatched() }
  }

  @Test
  fun adapterRejectsMissingWrongDuplicateOrChangedRoutes() {
    for (case in 0..6) {
      val configuration = config(strict())
      val verifier = StrictReplayVerifier(emptyList(), fingerprint)
      val adapter = StrictReplayObserverAdapter(verifier)
      if (case != 0) configuration.scheduleObservers.add(adapter)
      if (case != 1) configuration.testStatusObservers.add(adapter)
      when (case) {
        2 -> {
          configuration.testStatusObservers.clear()
          configuration.testStatusObservers.add(StrictReplayObserverAdapter(verifier))
        }
        3 -> configuration.scheduleObservers.add(adapter)
        4 -> configuration.testStatusObservers.add(verifier)
        5 -> configuration.scheduleObservers.add(StrictReplayObserverAdapter(verifier))
        6 -> configuration.scheduler = RandomScheduler()
      }
      if (case == 6) {
        assertFailsWith<IllegalStateException> {
          verifier.attachRandomnessVia(configuration, adapter)
        }
        continue
      }
      verifier.attachRandomnessVia(configuration, adapter)
      adapter.onExecutionStart()
      val error = appFailure()
      adapter.onReportError(error)
      adapter.onExecutionDone(error)
      assertFailsWith<IllegalStateException> { verifier.requireMatched() }
    }

    val configuration = config(strict())
    val verifier = StrictReplayVerifier(emptyList(), fingerprint)
    val adapter = StrictReplayObserverAdapter(verifier)
    configuration.scheduleObservers.add(adapter)
    configuration.testStatusObservers.add(adapter)
    verifier.attachRandomnessVia(configuration, adapter)
    adapter.onExecutionStart()
    val error = appFailure()
    adapter.onReportError(error)
    adapter.onExecutionDone(error)
    configuration.scheduleObservers.remove(adapter)
    assertFailsWith<IllegalStateException> { verifier.requireMatched() }
  }

  @Test
  fun adapterRetainsStrictRandomUnderflowAndOverrunChecks() {
    for (overrun in listOf(false, true)) {
      val stream = if (overrun) strict(4) else strict(4, 8)
      val configuration = config(stream)
      val verifier = StrictReplayVerifier(emptyList(), fingerprint)
      val adapter = StrictReplayObserverAdapter(verifier)
      configuration.scheduleObservers.add(adapter)
      configuration.testStatusObservers.add(adapter)
      verifier.attachRandomnessVia(configuration, adapter)
      adapter.onExecutionStart()
      assertEquals(4, stream.nextInt())
      if (overrun) assertFailsWith<IllegalStateException> { stream.nextInt() }
      val error = appFailure()
      adapter.onReportError(error)
      adapter.onExecutionDone(error)
      assertFailsWith<IllegalStateException> { verifier.requireMatched() }
    }
  }
}
