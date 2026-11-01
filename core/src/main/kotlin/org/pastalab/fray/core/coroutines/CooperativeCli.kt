package org.pastalab.fray.core.coroutines

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.pastalab.fray.core.command.MethodExecutor

/** Explicit trusted inputs for the experimental CLI; paths resolve relative to the config file. */
@Serializable data class CliPinnedFile(val path: String, val sha256: String)

@Serializable
data class CliRuntime(
    val java: CliPinnedFile,
    val modules: CliPinnedFile,
    val agent: CliPinnedFile,
    val jvmti: CliPinnedFile,
    val jars: List<CliPinnedFile>,
    val stdlibJar: String,
    val coroutineJar: String,
    val maxHeapMb: Int = 384,
    val activeProcessors: Int = 4,
)

@Serializable
data class CliApplication(val archive: CliPinnedFile, val classes: Map<String, String>)

@Serializable
data class CliConfiguration(
    val schema: Int = 1,
    val runtime: CliRuntime,
    val app: CliApplication,
    val entrypoint: Entrypoint,
    val outputDirectory: String,
    val timeoutMillis: Long = 60_000,
    val seeds: List<Long>,
    val expectedFailure: ExpectedApplicationFailure? = null,
)

@Serializable
private data class CliResult(
    val status: EpochStatus,
    val reason: String,
    val attempted: Int,
    val completed: Int,
    val seed: Long? = null,
    val artifactDirectory: String? = null,
    val strictReplayVerified: Boolean = false,
)

/** Internal seam allows deterministic CLI tests without launching a process. */
internal fun interface CliEpochExecutor {
  fun run(
      executor: MethodExecutor,
      seed: Long,
      runtime: EpochRuntimeSpec,
      timeoutMillis: Long,
      replay: ReplayInput?,
      expectedFailure: ExpectedApplicationFailure?,
      inputs: CooperativeExecutionInputs,
  ): EpochOutcome
}

/**
 * Experimental opt-in entrypoint. It does not modify the existing Fray CLI or JUnit integration.
 */
object CooperativeCli {
  @JvmStatic
  fun main(args: Array<String>) {
    exitProcess(CooperativeCliRunner().run(args.toList(), System.out, System.err))
  }
}

internal class CooperativeCliRunner(private val execute: CliEpochExecutor? = null) {
  companion object {
    private val json = Json {
      encodeDefaults = true
      ignoreUnknownKeys = false
    }
    private val sha = Regex("[0-9a-f]{64}")
    private val binaryName = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val methodName = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private const val MAX_CONFIG_BYTES = 1_048_576
    private const val MAX_ARTIFACT_BYTES = 32 * 1024 * 1024
  }

  fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
    fun emit(result: CliResult): Int {
      out.println(json.encodeToString(result))
      out.flush()
      return when (result.status) {
        EpochStatus.COMPLETED -> 0
        EpochStatus.APPLICATION_BUG -> 10
        EpochStatus.UNSUPPORTED -> 20
        EpochStatus.INCONCLUSIVE -> 30
      }
    }
    if (args == listOf("--help") || args == listOf("-h")) {
      out.println(
          "Experimental cooperative Kotlin/JVM subset; not a sandbox or general coroutine support."
      )
      out.println(
          "Usage: CooperativeCli run --config FILE | replay --config FILE --artifact-dir DIR"
      )
      out.println(
          "Requires a trusted pinned runtime, static no-arg entrypoint, structured jobs, and no host interop."
      )
      out.println(
          "Unexpected failures are inconclusive unless an expected failure site is configured."
      )
      return 0
    }
    var attempted = 0
    var completed = 0
    var currentSeed: Long? = null
    try {
      require(args.isNotEmpty() && args[0] in setOf("run", "replay")) { "Unknown command" }
      val options = linkedMapOf<String, String>()
      var index = 1
      while (index < args.size) {
        require(index + 1 < args.size && args[index] in setOf("--config", "--artifact-dir")) {
          "Invalid option"
        }
        require(options.putIfAbsent(args[index], args[index + 1]) == null) { "Duplicate option" }
        index += 2
      }
      require(options["--config"] != null) { "Missing config" }
      require((args[0] == "replay") == (options["--artifact-dir"] != null)) {
        "Invalid replay arguments"
      }
      val configPath =
          regularFile(Path.of(options.getValue("--config")).toAbsolutePath().normalize())
      val config =
          json.decodeFromString<CliConfiguration>(boundedText(configPath, MAX_CONFIG_BYTES))
      validate(config)
      val base = requireNotNull(configPath.parent)
      fun source(value: String): Path {
        require(value.isNotBlank()) { "Empty input path" }
        val path = Path.of(value)
        return regularFile((if (path.isAbsolute) path else base.resolve(path)).normalize())
      }
      fun pin(file: CliPinnedFile): PinnedFile {
        require(sha.matches(file.sha256)) { "Invalid SHA-256" }
        return PinnedFile(source(file.path), file.sha256)
      }
      val java = pin(config.runtime.java)
      val modules = pin(config.runtime.modules)
      val agent = pin(config.runtime.agent)
      val jvmti = pin(config.runtime.jvmti)
      val runtimeJars = config.runtime.jars.map(::pin)
      val app = pin(config.app.archive)
      require(
          runtimeJars.map { it.path }.distinct().size == runtimeJars.size &&
              runtimeJars.none { it.path == app.path }
      ) {
        "Duplicate or overlapping app/runtime JAR"
      }
      val stdlib = source(config.runtime.stdlibJar)
      val coroutines = source(config.runtime.coroutineJar)
      require(
          stdlib != coroutines &&
              runtimeJars.any { it.path == stdlib } &&
              runtimeJars.any { it.path == coroutines }
      ) {
        "Runtime JAR roles do not match manifest"
      }
      val runtime =
          EpochRuntimeSpec(
              java,
              modules,
              agent,
              jvmti,
              runtimeJars + listOf(app),
              config.runtime.maxHeapMb,
              config.runtime.activeProcessors,
          )
      val inputs =
          CooperativeExecutionInputs(
              app.path.toString(),
              app.sha256,
              config.app.classes.toMap(),
              stdlib.toString(),
              coroutines.toString(),
          )
      val method =
          MethodExecutor(
              config.entrypoint.className,
              config.entrypoint.methodName,
              emptyList(),
              runtime.jars.map { it.path.toString() },
              emptyMap(),
          )
      val replayDir =
          options["--artifact-dir"]?.let { raw ->
            val path = Path.of(raw).toAbsolutePath().normalize()
            require(
                !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            ) {
              "Invalid artifact directory"
            }
            path.toRealPath()
          }
      val replay =
          replayDir?.let { dir ->
            val manifestPath = regularFile(dir.resolve("manifest.json"))
            val manifest =
                json.decodeFromString<ReplayManifest>(boundedText(manifestPath, MAX_CONFIG_BYTES))
            require(
                config.expectedFailure == null || config.expectedFailure == manifest.expectedFailure
            ) {
              "Expected failure differs from replay"
            }
            ReplayInput(
                manifest,
                regularFile(dir.resolve("recording.json")),
                regularFile(dir.resolve("random.json")),
                regularFile(dir.resolve("strict-state-v1.json")),
            )
          }
      val rawOutput = Path.of(config.outputDirectory)
      val requestedOutput =
          (if (rawOutput.isAbsolute) rawOutput else base.resolve(rawOutput))
              .toAbsolutePath()
              .normalize()
      require(
          requestedOutput.fileName != null &&
              !Files.exists(requestedOutput, LinkOption.NOFOLLOW_LINKS)
      ) {
        "Output directory must not exist"
      }
      val outputParent = requireNotNull(requestedOutput.parent).toRealPath()
      require(Files.isDirectory(outputParent)) { "Output parent must exist" }
      val output = outputParent.resolve(requestedOutput.fileName.toString())
      val sourceFiles =
          listOf(configPath, java.path, modules.path, agent.path, jvmti.path, app.path) +
              runtimeJars.map { it.path }
      require(
          sourceFiles.none { it.startsWith(output) } &&
              (replayDir == null ||
                  (!output.startsWith(replayDir) && !replayDir.startsWith(output)))
      ) {
        "Output overlaps an input"
      }
      Files.createDirectory(output)
      val backend = CoroutineEpochBackend(output.resolve("scratch"))
      val launcher =
          execute
              ?: CliEpochExecutor {
                  target,
                  seed,
                  spec,
                  timeout,
                  replayInput,
                  expected,
                  runtimeInputs ->
                backend.run(target, true, seed, spec, timeout, replayInput, expected, runtimeInputs)
              }
      val seeds = replay?.let { listOf(it.manifest.seed) } ?: config.seeds
      val expected = replay?.manifest?.expectedFailure ?: config.expectedFailure
      for (seed in seeds) {
        currentSeed = seed
        attempted++
        val outcome =
            launcher.run(method, seed, runtime, config.timeoutMillis, replay, expected, inputs)
        when (outcome.status) {
          EpochStatus.COMPLETED -> {
            if (replay != null)
                return emit(
                    CliResult(
                        EpochStatus.INCONCLUSIVE,
                        "Replay did not reproduce the recorded failure",
                        attempted,
                        completed,
                        seed,
                    )
                )
            completed++
          }
          EpochStatus.APPLICATION_BUG -> {
            val response = requireNotNull(outcome.response) { "Missing bug response" }
            val manifest = requireNotNull(response.manifest) { "Missing replay manifest" }
            if (
                replay != null &&
                    (!response.evidence.strictReplayVerified || manifest != replay.manifest)
            ) {
              return emit(
                  CliResult(
                      EpochStatus.INCONCLUSIVE,
                      "Replay evidence does not match",
                      attempted,
                      completed,
                      seed,
                  )
              )
            }
            val sourceDir =
                requireNotNull(outcome.artifactDirectory) { "Missing recording directory" }
            val saved = persist(sourceDir, output.resolve("recording"), manifest)
            return emit(
                CliResult(
                    EpochStatus.APPLICATION_BUG,
                    "Expected application failure recorded",
                    attempted,
                    completed,
                    seed,
                    saved.toString(),
                    response.evidence.strictReplayVerified,
                )
            )
          }
          EpochStatus.UNSUPPORTED,
          EpochStatus.INCONCLUSIVE ->
              return emit(CliResult(outcome.status, outcome.reason, attempted, completed, seed))
        }
      }
      return emit(
          CliResult(
              EpochStatus.COMPLETED,
              "All requested iterations completed",
              attempted,
              completed,
          )
      )
    } catch (failure: IllegalArgumentException) {
      val status = if (attempted == 0) EpochStatus.UNSUPPORTED else EpochStatus.INCONCLUSIVE
      return emit(
          CliResult(
              status,
              if (attempted == 0) failure.message ?: "Invalid CLI request"
              else "Could not complete or preserve evidence",
              attempted,
              completed,
              currentSeed,
          )
      )
    } catch (failure: Exception) {
      err.println("Cooperative CLI could not complete: ${failure.javaClass.simpleName}")
      return emit(
          CliResult(
              EpochStatus.INCONCLUSIVE,
              "Could not complete or preserve evidence",
              attempted,
              completed,
              currentSeed,
          )
      )
    }
  }

  private fun validate(config: CliConfiguration) {
    require(
        config.schema == 1 &&
            config.seeds.isNotEmpty() &&
            config.seeds.size <= 100 &&
            config.seeds.distinct().size == config.seeds.size
    ) {
      "Invalid schema or seeds"
    }
    require(
        config.timeoutMillis in 1..600_000 &&
            config.runtime.maxHeapMb in 128..1024 &&
            config.runtime.activeProcessors in 1..4
    ) {
      "Invalid resource limits"
    }
    require(
        config.runtime.jars.isNotEmpty() &&
            config.runtime.jars.size <= 255 &&
            binaryName.matches(config.entrypoint.className) &&
            methodName.matches(config.entrypoint.methodName) &&
            config.outputDirectory.isNotBlank()
    ) {
      "Invalid entrypoint or runtime"
    }
    require(
        EpochProtocolValidator.validAppInventory(config.app.archive.sha256, config.app.classes) &&
            config.entrypoint.className.replace('.', '/') in config.app.classes &&
            config.expectedFailure?.isValid() != false &&
            (config.expectedFailure == null ||
                config.expectedFailure.topClass.replace('.', '/') in config.app.classes)
    ) {
      "Invalid app inventory or expected failure"
    }
  }

  private fun regularFile(path: Path): Path {
    require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      "Input is not a regular file"
    }
    return path.toRealPath()
  }

  private fun boundedText(path: Path, limit: Int): String {
    val bytes = Files.newInputStream(path).use { it.readNBytes(limit + 1) }
    require(bytes.size <= limit) { "Input exceeds size limit" }
    return bytes.toString(StandardCharsets.UTF_8)
  }

  private fun persist(source: Path, destination: Path, manifest: ReplayManifest): Path {
    require(!Files.isSymbolicLink(source) && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
      "Invalid recording source"
    }
    val names = listOf("recording.json", "random.json", "strict-state-v1.json")
    val expected =
        listOf(manifest.scheduleDigest, manifest.randomDigest, manifest.strictStateDigest)
    require(expected.all(sha::matches)) { "Invalid recording digest" }
    Files.createDirectory(destination)
    names.zip(expected).forEach { (name, expectedHash) ->
      val file = regularFile(source.resolve(name))
      require(hash(file) == expectedHash) { "Recording changed before persistence" }
      val copy = Files.copy(file, destination.resolve(name))
      require(hash(copy) == expectedHash) { "Recording copy mismatch" }
    }
    Files.writeString(
        destination.resolve("manifest.json"),
        json.encodeToString(manifest),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
    return destination
  }

  private fun hash(path: Path): String {
    require(Files.size(path) <= MAX_ARTIFACT_BYTES) { "Recording is too large" }
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_ARTIFACT_BYTES + 1) }
    require(bytes.size <= MAX_ARTIFACT_BYTES) { "Recording is too large" }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it.toInt() and 0xff)
    }
  }
}
