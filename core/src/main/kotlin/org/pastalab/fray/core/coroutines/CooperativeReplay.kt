package org.pastalab.fray.core.coroutines

import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlinx.serialization.json.Json
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.command.Configuration
import org.pastalab.fray.core.observers.ReplayFailureFingerprint
import org.pastalab.fray.core.observers.ScheduleRecording
import org.pastalab.fray.core.observers.StrictFailureExpectation
import org.pastalab.fray.core.observers.StrictReplayFiles
import org.pastalab.fray.core.observers.StrictReplayObserverAdapter
import org.pastalab.fray.core.observers.StrictReplayVerifier
import org.pastalab.fray.core.observers.StrictStateCapture
import org.pastalab.fray.core.observers.StrictStateFilesV1
import org.pastalab.fray.core.observers.StrictStateRecorderV1
import org.pastalab.fray.core.observers.StrictStateReplayVerifierV1
import org.pastalab.fray.core.observers.StrictStateTraceV1
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.Randomness
import org.pastalab.fray.core.randomness.RandomnessProvider
import org.pastalab.fray.core.randomness.StrictRecordedIntRandomness
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver

/** Controlled expected-failure experiment; matching traces is not a general replay guarantee. */
internal class CooperativeReplay
private constructor(
    private val request: EpochRequest,
    val capture: StrictStateCapture,
    private val strictRandom: StrictRecordedIntRandomness,
    private val recorder: StrictStateRecorderV1,
    private val legacyVerifier: StrictReplayVerifier,
    private val stateVerifier: StrictStateReplayVerifierV1,
) {
  val provider: RandomnessProvider =
      object : RandomnessProvider {
        override fun getRandomness(): Randomness =
            if (request.replay) strictRandom
            else ControlledRandom(mutableListOf(), mutableListOf(), Random(request.seed))
      }

  fun attach(config: Configuration) {
    val legacy = StrictReplayObserverAdapter(if (request.replay) legacyVerifier else null)
    if (request.replay) legacyVerifier.attachRandomnessVia(config, legacy)
    val observer =
        if (request.replay) SymmetricObserver(stateVerifier, stateVerifier)
        else SymmetricObserver(recorder, recorder)
    config.scheduleObservers.add(legacy)
    config.testStatusObservers.add(legacy)
    config.scheduleObservers.add(observer)
    config.testStatusObservers.add(observer)
  }

  fun requireSuccess() {
    if (request.replay) {
      legacyVerifier.requireMatched()
      stateVerifier.requireMatched()
      check(strictRandom.violation() == null) { "Recorded randomness was not consumed exactly" }
    } else recorder.requireRecorded()
  }

  companion object {
    fun prepare(request: EpochRequest, epoch: Path): CooperativeReplay {
      val expected = requireNotNull(request.expectedFailure)
      // Both modes create the same dummy files and parse using the same library paths before Fray
      // starts. This reduces bootstrap asymmetry; it does not prove identical JVM state.
      val bootstrap = Files.createDirectory(epoch.resolve("replay-bootstrap"))
      val dummyRandom = bootstrap.resolve("random.json")
      val dummySchedule = bootstrap.resolve("recording.json")
      val dummyState = bootstrap.resolve("strict-state-v1.json")
      Files.writeString(
          dummyRandom,
          "{\"type\":\"org.pastalab.fray.core.randomness.ControlledRandom\",\"integers\":[1]}",
      )
      Files.writeString(
          dummySchedule,
          "[{\"scheduled\":0,\"enabled\":[0],\"operation\":\"example.Read\"}]",
      )
      Files.writeString(
          dummyState,
          "{\"version\":1,\"failure\":{\"exceptionClass\":\"java.lang.IllegalStateException\",\"message\":\"dummy\",\"topClass\":\"example.Dummy\",\"topMethod\":\"run\",\"topFile\":\"Dummy.java\",\"topLine\":1},\"steps\":[{\"selectedIndex\":0,\"runnableIndices\":[0],\"threads\":[{\"index\":0,\"state\":\"Runnable\",\"pendingOperation\":\"example.Read\",\"ownedCoroutineWorker\":true,\"workerRngState\":1}]}]}",
      )
      val directory = if (request.replay) epoch.resolve("recording") else bootstrap
      val random =
          StrictRecordedIntRandomness.fromRecorded(
              Json.decodeFromString<Randomness>(Files.readString(directory.resolve("random.json")))
          )
      val schedule: List<ScheduleRecording> =
          StrictReplayFiles.read(directory.resolve("recording.json"))
      val state: StrictStateTraceV1 =
          StrictStateFilesV1.read(directory.resolve("strict-state-v1.json"))
      val failure =
          if (request.replay) requireNotNull(request.replayManifest).failure
          else
              FailureFingerprint(
                  "java.lang.IllegalStateException",
                  "dummy",
                  "example.Dummy",
                  "run",
                  "Dummy.java",
                  1,
              )
      val fingerprint =
          ReplayFailureFingerprint(
              failure.className,
              failure.message,
              StackTraceElement(
                  failure.topClass,
                  failure.topMethod,
                  failure.topFile,
                  failure.topLine,
              ),
          )
      val capture = StrictStateCapture()
      val recorder =
          StrictStateRecorderV1(
              capture,
              StrictFailureExpectation(
                  expected.className,
                  expected.topClass,
                  expected.topMethod,
                  expected.messagePrefix,
              ),
              true,
          )
      return CooperativeReplay(
          request,
          capture,
          random,
          recorder,
          StrictReplayVerifier(schedule, fingerprint),
          StrictStateReplayVerifierV1(capture, state, true),
      )
    }
  }
}

private class SymmetricObserver(
    private val schedule: ScheduleObserver<ThreadContext>,
    private val status: TestStatusObserver,
) : ScheduleObserver<ThreadContext>, TestStatusObserver {
  override fun onExecutionStart() = status.onExecutionStart()

  override fun onReportError(throwable: Throwable) = status.onReportError(throwable)

  override fun onExecutionDone(bugFound: Throwable?) = status.onExecutionDone(bugFound)

  override fun saveToReportFolder(path: Path) = status.saveToReportFolder(path)

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) =
      schedule.onContextSwitch(current, next)

  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) =
      schedule.onNewSchedule(allThreads, scheduled)
}
