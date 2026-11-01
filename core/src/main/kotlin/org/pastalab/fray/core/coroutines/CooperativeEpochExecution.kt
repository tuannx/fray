package org.pastalab.fray.core.coroutines

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.pastalab.fray.core.TestRunner
import org.pastalab.fray.core.command.Configuration
import org.pastalab.fray.core.command.ExecutionInfo
import org.pastalab.fray.core.command.FrayClassLoader
import org.pastalab.fray.core.command.FrozenTrustedRuntimeResolver
import org.pastalab.fray.core.command.LambdaExecutor
import org.pastalab.fray.core.command.NetworkDelegateType
import org.pastalab.fray.core.command.SystemTimeDelegateType
import org.pastalab.fray.core.observers.ScheduleRecorder
import org.pastalab.fray.core.observers.StrictStateCapturingScheduler
import org.pastalab.fray.core.observers.TimelineCoverageType
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.Randomness
import org.pastalab.fray.core.randomness.RandomnessProvider
import org.pastalab.fray.core.scheduler.RandomScheduler
import org.pastalab.fray.core.utils.HelperThread
import org.pastalab.fray.instrumentation.base.VerifiedCooperativeGraph
import org.pastalab.fray.rmi.TestStatusObserver
import org.pastalab.fray.runtime.CoroutineEpochGate
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners
import org.pastalab.fray.runtime.CoroutineJobTracker
import org.pastalab.fray.runtime.CoroutineTaskTracker
import org.pastalab.fray.runtime.Runtime

/**
 * Disposable-child-only execution of one successful cooperative workload. Caller must first pin the
 * JVM/agent chain, verify the app archive and construct a trusted runtime resolver. This engine is
 * not a security boundary, a generic bug classifier, or a replay implementation.
 */
internal object CooperativeEpochExecution {
  fun run(
      request: EpochRequest,
      graph: VerifiedCooperativeGraph,
      coroutineUrls: Array<URL>,
      resolver: FrozenTrustedRuntimeResolver,
      reportPath: Path,
  ): EpochResponse {
    fun response(status: EpochStatus, reason: String) =
        EpochResponse(nonce = request.nonce, status = status, reason = reason)
    if (request.replay && (request.expectedFailure == null || request.replayManifest == null))
        return response(
            EpochStatus.UNSUPPORTED,
            "Replay requires an explicit expected failure and manifest",
        )
    if (
        graph.entryClass != request.entrypoint.className.replace('.', '/') ||
            graph.entryMethod != request.entrypoint.methodName
    )
        return response(EpochStatus.UNSUPPORTED, "Entrypoint differs from verified app graph")
    val uncaught = AtomicReference<Throwable?>()
    Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.compareAndSet(null, error) }
    val state = EpochSentinels()
    val appExecutor = LambdaExecutor {
      executeWorkload(request, graph, coroutineUrls, resolver, state)
    }
    val replay =
        request.expectedFailure?.let { CooperativeReplay.prepare(request, reportPath.parent) }
    val randomProvider =
        replay?.provider
            ?: object : RandomnessProvider {
              override fun getRandomness(): Randomness =
                  ControlledRandom(mutableListOf(), mutableListOf(), Random(request.seed))
            }
    val baseScheduler = RandomScheduler(randomProvider.getRandomness())
    val scheduler =
        if (replay == null) baseScheduler
        else StrictStateCapturingScheduler(baseScheduler, replay.capture)
    val config =
        Configuration(
            executionInfo = ExecutionInfo(appExecutor, false, false, 10_000),
            report = reportPath,
            iter = 1,
            timeout = 30,
            scheduler = scheduler,
            randomnessProvider = randomProvider,
            fullSchedule = true,
            exploreMode = false,
            noExitWhenBugFound = true,
            isReplay = request.replay,
            noFray = false,
            dummyRun = false,
            networkDelegateType = NetworkDelegateType.REACTIVE,
            systemTimeDelegateType = SystemTimeDelegateType.MOCK,
            virtualTimeDelta = 100_000L,
            ignoreTimedBlock = true,
            sleepAsYield = false,
            resetClassLoader = true,
            redirectStdout = false,
            abortThreadExecutionAfterMainExit = false,
            resolveRacingOperationStackTraceHash = false,
            timelineCoverageType = TimelineCoverageType.NONE,
        )
    if (replay != null) {
      // Install the legacy recorder and both replay adapter routes in the same order in both modes.
      val scheduleRecorder = ScheduleRecorder()
      config.scheduleObservers.add(scheduleRecorder)
      config.testStatusObservers.add(scheduleRecorder)
      replay.attach(config)
    }
    config.testStatusObservers.add(state)
    // Capture exactly the helper owned by the managed context, before Runtime.start. It lives in
    // the parent loader and cannot be found reliably by scanning for the child loader.
    val helpers = CopyOnWriteArrayList<HelperThread>()
    val result =
        TestRunner(
                config,
                Consumer { context ->
                  context.executor.enableFailureRetention()
                  helpers.add(context.executor)
                },
            )
            .run()
    val helper = helpers.singleOrNull()
    val helperStopped = helper != null && helper.awaitTermination(2_000)
    val helperFailure = helper?.firstFailure
    result?.let(CooperativeDiagnostics::failure)
    helperFailure?.let(CooperativeDiagnostics::failure)
    uncaught.get()?.let(CooperativeDiagnostics::failure)
    when (
        unsupportedDisposition(
            state.unsupported,
            state.unsupportedCause,
            result,
            helperStopped,
            helperFailure,
            uncaught.get(),
        )
    ) {
      EpochStatus.UNSUPPORTED -> return response(EpochStatus.UNSUPPORTED, state.unsupported!!)
      EpochStatus.INCONCLUSIVE ->
          return response(
              EpochStatus.INCONCLUSIVE,
              "Workload, lifecycle or Fray completion was not verified",
          )
      else -> Unit
    }
    if (
        uncaught.get() != null ||
            !helperStopped ||
            helperFailure != null ||
            !state.isComplete() ||
            result !== state.applicationFailure
    ) {
      return response(
          EpochStatus.INCONCLUSIVE,
          "Workload, lifecycle or Fray completion was not verified",
      )
    }
    val failure = state.applicationFailure
    if (failure == null) {
      if (request.replay)
          return response(EpochStatus.INCONCLUSIVE, "Replay did not reproduce the expected failure")
      return EpochResponse(
          nonce = request.nonce,
          status = EpochStatus.COMPLETED,
          evidence =
              EpochEvidence(
                  admissionVerified = true,
                  workloadTerminated = true,
                  jobBoundaryVerified = true,
                  callbacksDrained = true,
                  teardownComplete = true,
                  noUncaughtFailure = true,
              ),
      )
    }
    val fingerprint =
        fingerprint(failure, request, graph)
            ?: return response(
                EpochStatus.INCONCLUSIVE,
                "Failure is not the expected application site",
            )
    if (replay == null)
        return response(EpochStatus.INCONCLUSIVE, "Expected-failure recorder was not configured")
    try {
      replay.requireSuccess()
    } catch (error: Exception) {
      CooperativeDiagnostics.failure(error)
      return response(EpochStatus.INCONCLUSIVE, "Strict recording or replay did not match")
    }
    val manifest =
        if (request.replay) requireNotNull(request.replayManifest)
        else {
          val recording = Files.createDirectory(reportPath.parent.resolve("recording"))
          val source = reportPath.resolve("recording")
          val names = listOf("recording.json", "random.json", "strict-state-v1.json")
          names.forEach { Files.copy(source.resolve(it), recording.resolve(it)) }
          fun hash(name: String): String {
            val path = recording.resolve(name)
            require(Files.size(path) <= CoroutineEpochBackend.MAX_ARTIFACT_BYTES)
            return MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
          }
          val inputs = requireNotNull(request.cooperativeInputs)
          ReplayManifest(
              entrypoint = request.entrypoint,
              seed = request.seed,
              classpathDigest = request.classpathDigest,
              childClasspathDigest = request.childClasspathDigest,
              runtimeDigest = request.runtimeDigest,
              processPolicyDigest = request.processPolicyDigest,
              appJarSha256 = inputs.appJarSha256,
              appClasses = inputs.appClasses,
              scheduleDigest = hash(names[0]),
              randomDigest = hash(names[1]),
              strictStateDigest = hash(names[2]),
              expectedFailure = requireNotNull(request.expectedFailure),
              failure = fingerprint,
          )
        }
    if (manifest.failure != fingerprint)
        return response(EpochStatus.INCONCLUSIVE, "Replay failure fingerprint differs")
    return EpochResponse(
        nonce = request.nonce,
        status = EpochStatus.APPLICATION_BUG,
        failure = fingerprint,
        manifest = manifest,
        evidence =
            EpochEvidence(
                admissionVerified = true,
                workloadTerminated = true,
                jobBoundaryVerified = true,
                callbacksDrained = true,
                teardownComplete = true,
                noUncaughtFailure = true,
                strictReplayVerified = request.replay,
            ),
    )
  }

  private fun fingerprint(
      error: Throwable,
      request: EpochRequest,
      graph: VerifiedCooperativeGraph,
  ): FailureFingerprint? =
      classifyExpectedFailure(error, request.expectedFailure, graph.classNames())

  private fun executeWorkload(
      request: EpochRequest,
      graph: VerifiedCooperativeGraph,
      coroutineUrls: Array<URL>,
      resolver: FrozenTrustedRuntimeResolver,
      state: EpochSentinels,
  ) {
    val original = Thread.currentThread().contextClassLoader
    lateinit var loader: FrayClassLoader
    try {
      Runtime.onSkipPrimitive("cooperative child setup")
      val target: java.lang.reflect.Method
      try {
        loader = FrayClassLoader(coroutineUrls, original, true, graph, emptyMap(), resolver)
        Thread.currentThread().contextClassLoader = loader
        val root = Class.forName("kotlinx.coroutines.BlockingCoroutine", false, loader)
        CoroutineEpochGate.begin(loader)
        CoroutineJobTracker.begin(loader, root, Thread.currentThread())
        CoroutineTaskTracker.begin(loader)
        val targetClass = Class.forName(request.entrypoint.className, false, loader)
        require(targetClass.classLoader === loader)
        target = targetClass.getDeclaredMethod(request.entrypoint.methodName)
        require(
            Modifier.isPublic(target.modifiers) &&
                Modifier.isStatic(target.modifiers) &&
                target.returnType == Void.TYPE &&
                target.parameterCount == 0
        )
        state.setup = true
      } finally {
        Runtime.onSkipPrimitiveDone("cooperative child setup")
      }
      val applicationFailure =
          try {
            target.invoke(null)
            null
          } catch (error: InvocationTargetException) {
            error.targetException
          }
      if (applicationFailure != null && fingerprint(applicationFailure, request, graph) == null)
          throw applicationFailure
      state.applicationFailure = applicationFailure
      state.workloadReturned = true
      repeat(16) { Thread.yield() }
      try {
        CoroutineEpochGate.atomicSeal(loader, applicationFailure)
        state.boundarySealed = true
      } catch (error: IllegalStateException) {
        state.unsupported = "Coroutine ownership or quiescence was rejected"
        state.unsupportedCause = error
        throw error
      }
      val dispatchers = Class.forName("kotlinx.coroutines.Dispatchers", true, loader)
      val defaultExecutor = Class.forName("kotlinx.coroutines.DefaultExecutor", true, loader)
      check(dispatchers.classLoader === loader && defaultExecutor.classLoader === loader)
      val threadField = defaultExecutor.getDeclaredField("_thread")
      val statusField = defaultExecutor.getDeclaredField("debugStatus")
      check(
          threadField.type == Thread::class.java &&
              statusField.type == Int::class.javaPrimitiveType &&
              Modifier.isStatic(threadField.modifiers) &&
              Modifier.isStatic(statusField.modifiers) &&
              threadField.trySetAccessible() &&
              statusField.trySetAccessible()
      )
      if (threadField.get(null) != null || statusField.getInt(null) != 0) {
        state.unsupported = "DefaultExecutor work is unsupported"
        val error = IllegalStateException("DefaultExecutor started")
        state.unsupportedCause = error
        throw error
      }
      dispatchers.getMethod("shutdown").invoke(dispatchers.getField("INSTANCE").get(null))
      check(threadField.get(null) == null && statusField.getInt(null) == 4)
      Runtime.onSkipPrimitive("cooperative child termination check")
      try {
        check(
            Thread.getAllStackTraces().keys.none {
              it !== Thread.currentThread() &&
                  it.isAlive &&
                  (it.javaClass.classLoader === loader || it.contextClassLoader === loader)
            }
        )
      } finally {
        Runtime.onSkipPrimitiveDone("cooperative child termination check")
      }
      CoroutineEpochGate.verifySealed(loader)
      loader.checkFrozenBoundary()
      CoroutineEpochGate.endAfterTermination(loader)
      loader.close()
      check(!CoroutineInstrumentationOwners.owns(loader))
      state.teardown = true
      if (applicationFailure != null) throw applicationFailure
    } catch (error: ClassNotFoundException) {
      state.unsupported = "Unknown class is outside the cooperative runtime policy"
      state.unsupportedCause = error
      throw error
    } catch (error: NoClassDefFoundError) {
      state.unsupported = "Unknown class is outside the cooperative runtime policy"
      state.unsupportedCause = error
      throw error
    } finally {
      Thread.currentThread().contextClassLoader = original
    }
  }

  private class EpochSentinels : TestStatusObserver {
    @Volatile var setup = false
    @Volatile var workloadReturned = false
    @Volatile var boundarySealed = false
    @Volatile var teardown = false
    @Volatile var applicationFailure: Throwable? = null
    @Volatile private var errorMismatch = false
    @Volatile var unsupported: String? = null
    @Volatile var unsupportedCause: Throwable? = null
    @Volatile private var starts = 0
    @Volatile private var ends = 0
    @Volatile private var errors = 0

    override fun onExecutionStart() {
      starts++
    }

    override fun onReportError(throwable: Throwable) {
      errors++
      if (throwable !== applicationFailure) errorMismatch = true
    }

    override fun onExecutionDone(bugFound: Throwable?) {
      ends++
      if (bugFound !== applicationFailure) errorMismatch = true
    }

    override fun saveToReportFolder(path: Path) {}

    fun isComplete(): Boolean =
        setup &&
            workloadReturned &&
            boundarySealed &&
            teardown &&
            starts == 1 &&
            ends == 1 &&
            !errorMismatch &&
            errors == if (applicationFailure == null) 0 else 1
  }
}

/** An expected rejection cannot hide a concurrent framework, helper or uncaught failure. */
internal fun unsupportedDisposition(
    reason: String?,
    rejection: Throwable?,
    result: Throwable?,
    helperStopped: Boolean,
    helperFailure: Throwable?,
    uncaught: Throwable?,
): EpochStatus? {
  if (!helperStopped || helperFailure != null || uncaught != null) return EpochStatus.INCONCLUSIVE
  if (reason == null) return null
  return if (rejection != null && result === rejection) EpochStatus.UNSUPPORTED
  else EpochStatus.INCONCLUSIVE
}
