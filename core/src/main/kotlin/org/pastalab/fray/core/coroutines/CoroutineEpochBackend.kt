package org.pastalab.fray.core.coroutines

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.pastalab.fray.core.command.Executor
import org.pastalab.fray.core.command.MethodExecutor
import org.pastalab.fray.core.observers.STRICT_STATE_V1_FILE
import org.pastalab.fray.core.observers.ScheduleRecording
import org.pastalab.fray.core.observers.StrictStateCapture
import org.pastalab.fray.core.observers.StrictStateReplayVerifierV1
import org.pastalab.fray.core.observers.StrictStateTraceV1
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.Randomness

/** File hashes must come from a trusted pinned runtime manifest, not be guessed at launch time. */
internal data class PinnedFile(val path: Path, val sha256: String)

/** Lab-only: jar-only classpath and a fixed JVM command; it accepts no arbitrary JVM options. */
internal data class EpochRuntimeSpec(
    val java: PinnedFile,
    val modules: PinnedFile,
    val agent: PinnedFile,
    val jvmti: PinnedFile,
    val jars: List<PinnedFile>,
    val maxHeapMb: Int = 384,
    val activeProcessors: Int = 4,
)

internal data class ReplayInput(
    val manifest: ReplayManifest,
    val schedule: Path,
    val random: Path,
    val strictState: Path,
)

/**
 * Supervises exactly one disposable child per invocation. It is not yet integrated into Fray's
 * public test APIs. The current child deliberately returns UNSUPPORTED: admission, lifecycle,
 * replay verification and status reporting are not yet implemented there.
 *
 * A subprocess isolates Java references between epochs; it does not prevent filesystem/network side
 * effects or escape by arbitrary native code. The caller and child are trusted, and this is not a
 * general security sandbox. Pre/post file hashes do not protect against concurrent mutation. Never
 * turn a timeout, malformed protocol or absent evidence into a completed test. Concurrent Fray test
 * executions are outside this experimental mode.
 */
internal class CoroutineEpochBackend(private val scratchRoot: Path) {
  companion object {
    internal const val MAX_PROTOCOL_BYTES = 1_048_576
    internal const val MAX_ARTIFACT_BYTES = 32L * 1024 * 1024
    internal const val MAX_OUTPUT_BYTES = 256 * 1024
    internal val FIXED_JVM_FLAGS = listOf("-ea", "-XX:+DisableAttachMechanism")
    private const val WORKER_CLASS = "org.pastalab.fray.core.coroutines.CoroutineEpochWorker"
    private const val WORKER_RESOURCE =
        "org/pastalab/fray/core/coroutines/CoroutineEpochWorker.class"
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val methodPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    // Do not pass the host's credentials, JVM injection variables, PATH, or arbitrary app state.
    private val allowedEnvironment =
        setOf("LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "SYSTEMROOT", "WINDIR")
    private val json = Json {
      encodeDefaults = true
      ignoreUnknownKeys = false
    }
  }

  /** No lambda, reused loader, method arguments or arbitrary system-property injection. */
  fun run(
      executor: Executor,
      resetClassLoader: Boolean,
      seed: Long,
      runtime: EpochRuntimeSpec,
      timeoutMillis: Long,
      replay: ReplayInput? = null,
      expectedFailure: ExpectedApplicationFailure? = null,
      cooperativeInputs: CooperativeExecutionInputs? = null,
  ): EpochOutcome {
    val method =
        executor as? MethodExecutor
            ?: return EpochOutcome(
                EpochStatus.UNSUPPORTED,
                "Only a static no-argument MethodExecutor is supported",
            )
    if (
        !resetClassLoader ||
            method.args.isNotEmpty() ||
            method.properties.isNotEmpty() ||
            !classPattern.matches(method.clazz) ||
            !methodPattern.matches(method.method) ||
            method.classpaths.isEmpty()
    ) {
      return EpochOutcome(
          EpochStatus.UNSUPPORTED,
          "Unsupported entrypoint, arguments, properties or reused classloader",
      )
    }
    if (timeoutMillis !in 1..TimeUnit.MINUTES.toMillis(10)) {
      return EpochOutcome(
          EpochStatus.UNSUPPORTED,
          "Epoch timeout must be between 1 ms and 10 minutes",
      )
    }
    if (expectedFailure?.isValid() == false || (replay != null && expectedFailure == null)) {
      return EpochOutcome(
          EpochStatus.UNSUPPORTED,
          "Expected application failure is missing or invalid",
      )
    }
    return try {
      runVerified(method, seed, runtime, timeoutMillis, replay, expectedFailure, cooperativeInputs)
    } catch (e: Exception) {
      EpochOutcome(
          EpochStatus.INCONCLUSIVE,
          "Could not launch or verify isolated epoch: ${e.javaClass.simpleName}",
      )
    }
  }

  private fun runVerified(
      method: MethodExecutor,
      seed: Long,
      runtime: EpochRuntimeSpec,
      timeoutMillis: Long,
      replay: ReplayInput?,
      expectedFailure: ExpectedApplicationFailure?,
      cooperativeInputs: CooperativeExecutionInputs?,
  ): EpochOutcome {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val frozenInputs = cooperativeInputs?.copy(appClasses = cooperativeInputs.appClasses.toMap())
    require(runtime.maxHeapMb in 128..1024 && runtime.activeProcessors in 1..4)
    require(runtime.jars.isNotEmpty() && runtime.jars.size <= 256)
    val pinned = listOf(runtime.java, runtime.modules, runtime.agent, runtime.jvmti) + runtime.jars
    pinned.forEach { verifyPinnedFile(it, deadline) }
    val java = runtime.java.path.toRealPath()
    val modules = runtime.modules.path.toRealPath()
    require(java.fileName.toString() == "java" && java.parent.fileName.toString() == "bin")
    require(modules == java.parent.parent.resolve("lib/modules").toRealPath())
    require(Files.isExecutable(java))
    val jars = runtime.jars.map { it.path.toRealPath() }
    require(
        jars.distinct().size == jars.size && jars.all { it.fileName.toString().endsWith(".jar") }
    )
    val appPaths = method.classpaths.map { Path.of(it).toRealPath() }
    // The explicit executor inventory includes the pinned app and all ordered runtime jars.
    require(appPaths == jars)
    var childClasspath = jars
    if (frozenInputs != null) {
      val inputs = frozenInputs
      val app = Path.of(inputs.appJar).toRealPath()
      val stdlib = Path.of(inputs.stdlibJar).toRealPath()
      val coroutines = Path.of(inputs.coroutineJar).toRealPath()
      require(
          setOf(app, stdlib, coroutines).size == 3 &&
              listOf(app, stdlib, coroutines).all { it in jars }
      )
      require(runtime.jars.single { it.path.toRealPath() == app }.sha256 == inputs.appJarSha256)
      require(EpochProtocolValidator.validAppInventory(inputs.appJarSha256, inputs.appClasses))
      // Never expose app bytes or resources to the parent/SystemClassLoader in the child.
      childClasspath = jars.filter { it != app }
    }
    require(
        childClasspath.count { jar ->
          JarFile(jar.toFile()).use { it.getJarEntry(WORKER_RESOURCE) != null }
        } == 1
    ) {
      "Expected exactly one worker in the child classpath"
    }
    val classpathDigest = digestOf(runtime.jars.map { it.sha256 })
    val childClasspathDigest =
        digestOf(
            runtime.jars
                .zip(jars)
                .filter { (_, path) -> path in childClasspath }
                .map { (pin, _) -> pin.sha256 }
        )
    val runtimeDigest =
        digestOf(
            listOf(
                runtime.java.sha256,
                runtime.modules.sha256,
                runtime.agent.sha256,
                runtime.jvmti.sha256,
            )
        )
    val childEnvironment = System.getenv().filterKeys { it in allowedEnvironment }
    require(
        childEnvironment.values.all {
          it.length <= 4096 && !it.contains('\n') && !it.contains('\r')
        }
    )
    val policyDigest =
        processPolicyDigest(runtime.maxHeapMb, runtime.activeProcessors, childEnvironment)
    val scratch = scratchRoot.toAbsolutePath().normalize()
    Files.createDirectories(scratch)
    require(!Files.isSymbolicLink(scratch) && Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS))
    val epoch = Files.createTempDirectory(scratch, "coroutine-epoch-")
    val entrypoint = Entrypoint(method.clazz, method.method)
    if (replay != null) {
      val m = replay.manifest
      require(
          m.schema == 4 &&
              frozenInputs != null &&
              EpochProtocolValidator.validAppInventory(m.appJarSha256, m.appClasses) &&
              m.appJarSha256 == frozenInputs.appJarSha256 &&
              m.appClasses == frozenInputs.appClasses &&
              m.childClasspathDigest == childClasspathDigest &&
              m.expectedFailure == expectedFailure &&
              m.strictStateVersion == 1 &&
              m.entrypoint == entrypoint &&
              m.seed == seed &&
              m.classpathDigest == classpathDigest &&
              m.runtimeDigest == runtimeDigest &&
              m.processPolicyDigest == policyDigest &&
              m.coroutineVersion == "1.10.1" &&
              m.freshProcessOnly &&
              digestPattern.matches(m.scheduleDigest) &&
              digestPattern.matches(m.randomDigest) &&
              digestPattern.matches(m.strictStateDigest)
      )
      val directory = Files.createDirectory(epoch.resolve("recording"))
      fun copyVerified(source: Path, target: Path, expected: String) {
        require(
            !Files.isSymbolicLink(source) && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
        )
        require(sha256(source, MAX_ARTIFACT_BYTES, deadline) == expected)
        Files.copy(source, target)
        require(sha256(target, MAX_ARTIFACT_BYTES, deadline) == expected)
      }
      copyVerified(replay.schedule, directory.resolve("recording.json"), m.scheduleDigest)
      copyVerified(replay.random, directory.resolve("random.json"), m.randomDigest)
      copyVerified(replay.strictState, directory.resolve(STRICT_STATE_V1_FILE), m.strictStateDigest)
      require(verifyArtifacts(epoch, m, deadline)) { "invalid replay artifact" }
    }
    val request =
        EpochRequest(
            nonce = UUID.randomUUID().toString(),
            entrypoint = entrypoint,
            seed = seed,
            classpathDigest = classpathDigest,
            childClasspathDigest = childClasspathDigest,
            runtimeDigest = runtimeDigest,
            processPolicyDigest = policyDigest,
            expectedFailure = expectedFailure,
            cooperativeInputs = frozenInputs,
            replay = replay != null,
            replayManifest = replay?.manifest,
        )
    val input = epoch.resolve("request.json")
    val result = epoch.resolve("response.json")
    Files.writeString(input, json.encodeToString(request), StandardCharsets.UTF_8)
    val agent = runtime.agent.path.toRealPath().toString()
    val native = runtime.jvmti.path.toRealPath().toString()
    require(
        !agent.contains('=') &&
            !native.contains('=') &&
            !agent.contains('\n') &&
            !native.contains('\n')
    )
    val command =
        listOf(
            java.toString(),
            "-Xmx${runtime.maxHeapMb}m",
            "-XX:ActiveProcessorCount=${runtime.activeProcessors}",
            *FIXED_JVM_FLAGS.toTypedArray(),
            "-agentpath:$native",
            "-javaagent:$agent",
            "-cp",
            childClasspath.joinToString(File.pathSeparator),
            WORKER_CLASS,
            input.toString(),
            result.toString(),
        )
    val builder = ProcessBuilder(command).directory(epoch.toFile()).redirectErrorStream(true)
    builder.environment().clear()
    builder.environment().putAll(childEnvironment)
    val child = builder.start()
    val diagnosticLog = epoch.resolve("worker.log")
    fun inconclusive(message: String) =
        EpochOutcome(EpochStatus.INCONCLUSIVE, message, diagnosticLog = diagnosticLog)
    val overflow = AtomicBoolean(false)
    val reader =
        Thread(
            {
              try {
                child.inputStream.use { stream ->
                  Files.newOutputStream(diagnosticLog, StandardOpenOption.CREATE_NEW).use { output
                    ->
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                      val count = stream.read(buffer)
                      if (count < 0) break
                      val accepted = minOf(count, MAX_OUTPUT_BYTES - total)
                      if (accepted > 0) output.write(buffer, 0, accepted)
                      total += accepted
                      if (accepted != count) {
                        overflow.set(true)
                        child.destroyForcibly()
                        break
                      }
                    }
                  }
                }
              } catch (_: Exception) {
                overflow.set(true)
              }
            },
            "coroutine-epoch-output",
        )
    reader.isDaemon = true
    reader.start()
    val remaining = deadline - System.nanoTime()
    if (remaining <= 0) {
      stopChild(child)
      return inconclusive("Child exceeded epoch deadline")
    }
    val exited =
        try {
          child.waitFor(remaining, TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
          stopChild(child)
          Thread.currentThread().interrupt()
          return inconclusive("Interrupted while waiting for child")
        }
    if (!exited) {
      stopChild(child)
      return inconclusive("Child exceeded epoch deadline")
    }
    try {
      val readRemainingMillis =
          TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 2_000)
      reader.join(readRemainingMillis)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return inconclusive("Interrupted while reading child output")
    }
    if (reader.isAlive || overflow.get()) return inconclusive("Child output was not safely bounded")
    try {
      pinned.forEach { verifyPinnedFile(it, deadline) }
    } catch (_: Exception) {
      return inconclusive("Pinned runtime changed or deadline expired")
    }
    val response =
        try {
          readResponse(result)
        } catch (_: Exception) {
          return inconclusive("Child response missing or malformed")
        }
    val outcome = EpochProtocolValidator.validate(request, response, child.exitValue())
    if (
        outcome.status == EpochStatus.APPLICATION_BUG &&
            !verifyArtifacts(epoch, response.manifest!!, deadline)
    ) {
      return inconclusive("Replay artifact missing, changed or too large")
    }
    return if (outcome.status == EpochStatus.APPLICATION_BUG)
        outcome.copy(artifactDirectory = epoch.resolve("recording"), diagnosticLog = diagnosticLog)
    else outcome.copy(diagnosticLog = diagnosticLog)
  }

  private fun readResponse(path: Path): EpochResponse {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_PROTOCOL_BYTES + 1) }
    require(bytes.size <= MAX_PROTOCOL_BYTES)
    return json.decodeFromString<EpochResponse>(bytes.toString(StandardCharsets.UTF_8))
  }

  internal fun verifyArtifacts(epoch: Path, manifest: ReplayManifest, deadline: Long): Boolean =
      try {
        val directory = epoch.resolve("recording")
        require(
            !Files.isSymbolicLink(directory) &&
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        )
        val root = epoch.toRealPath()
        fun checked(name: String, expected: String): String {
          val path = directory.resolve(name)
          require(
              !Files.isSymbolicLink(path) &&
                  Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                  path.toRealPath().startsWith(root) &&
                  Files.size(path) <= MAX_ARTIFACT_BYTES
          )
          require(System.nanoTime() < deadline)
          val bytes =
              Files.newInputStream(path).use { it.readNBytes(MAX_ARTIFACT_BYTES.toInt() + 1) }
          require(bytes.size <= MAX_ARTIFACT_BYTES && System.nanoTime() < deadline)
          val hash =
              MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
              }
          require(hash == expected)
          return bytes.toString(StandardCharsets.UTF_8)
        }
        val trace =
            json.decodeFromString<List<ScheduleRecording>>(
                checked("recording.json", manifest.scheduleDigest)
            )
        require(
            trace.isNotEmpty() &&
                trace.all {
                  it.scheduled >= 0 &&
                      it.enabled.isNotEmpty() &&
                      it.scheduled in it.enabled &&
                      it.enabled.all { id -> id >= 0 } &&
                      it.operation.isNotBlank() &&
                      it.operation.length <= 512
                }
        )
        require(
            manifest.schema == 4 &&
                EpochProtocolValidator.validAppInventory(
                    manifest.appJarSha256,
                    manifest.appClasses,
                ) &&
                digestPattern.matches(manifest.childClasspathDigest) &&
                manifest.strictStateVersion == 1 &&
                manifest.expectedFailure.matches(manifest.failure)
        )
        val strict =
            json.decodeFromString<StrictStateTraceV1>(
                checked(STRICT_STATE_V1_FILE, manifest.strictStateDigest)
            )
        require(strict.version == manifest.strictStateVersion)
        val f = manifest.failure
        require(
            strict.failure.exceptionClass == f.className &&
                strict.failure.message == f.message &&
                strict.failure.topClass == f.topClass &&
                strict.failure.topMethod == f.topMethod &&
                strict.failure.topFile == f.topFile &&
                strict.failure.topLine == f.topLine
        )
        StrictStateReplayVerifierV1(
            StrictStateCapture(),
            strict,
        ) // structural validation; not replay proof
        // The legacy "enabled" list is registered threads, not runnable candidates. Both
        // observers record that same collection at each scheduler decision. Compare it as a set:
        // the strict trace sorts its snapshots, while the legacy observer preserves map order.
        require(trace.size == strict.steps.size)
        trace.zip(strict.steps).forEach { (legacy, state) ->
          require(legacy.enabled.distinct().size == legacy.enabled.size)
          require(legacy.enabled.sorted() == state.threads.map { it.index })
          require(legacy.scheduled == state.selectedIndex)
          val selected = state.threads.single { it.index == state.selectedIndex }
          require(legacy.operation == selected.pendingOperation)
        }
        val random =
            json.decodeFromString<Randomness>(checked("random.json", manifest.randomDigest))
        require(
            random is ControlledRandom &&
                random.doubles.isEmpty() &&
                random.integers.all { it >= 0 && it < Int.MAX_VALUE }
        )
        true
      } catch (_: Exception) {
        false
      }

  private fun stopChild(child: Process) {
    // Only descendants of the process created above are touched. No process-name based killing.
    runCatching { child.descendants().use { stream -> stream.forEach { it.destroyForcibly() } } }
    child.destroyForcibly()
    try {
      child.waitFor(2, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun verifyPinnedFile(file: PinnedFile, deadline: Long) {
    require(
        digestPattern.matches(file.sha256) &&
            !Files.isSymbolicLink(file.path) &&
            Files.isRegularFile(file.path, LinkOption.NOFOLLOW_LINKS)
    )
    require(sha256(file.path, 2L * 1024 * 1024 * 1024, deadline) == file.sha256)
  }

  private fun digestOf(values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (value in values) {
      val bytes = value.toByteArray(StandardCharsets.UTF_8)
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
      digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  /**
   * Includes every fixed JVM flag and worker entrypoint; change the version when policy changes.
   */
  internal fun processPolicyDigest(
      heapMb: Int,
      processors: Int,
      environment: Map<String, String> = emptyMap(),
  ): String =
      digestOf(
          listOf(
              "fray-coroutine-process-policy-v3",
              "-Xmx${heapMb}m",
              "-XX:ActiveProcessorCount=$processors",
              *FIXED_JVM_FLAGS.toTypedArray(),
              "-agentpath:<pinned>",
              "-javaagent:<pinned>",
              "-cp:<ordered-pinned-runtime-jars-excluding-app>",
              WORKER_CLASS,
              "environment:allow:" + allowedEnvironment.sorted().joinToString(","),
          ) + environment.toSortedMap().flatMap { listOf(it.key, it.value) }
      )

  private fun sha256(path: Path, maxBytes: Long, deadline: Long): String {
    require(Files.size(path) <= maxBytes)
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(32 * 1024)
      var total = 0L
      while (true) {
        require(System.nanoTime() < deadline) { "epoch deadline exceeded while hashing" }
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes)
        md.update(buffer, 0, count)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
