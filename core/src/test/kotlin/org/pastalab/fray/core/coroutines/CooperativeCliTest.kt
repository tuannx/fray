package org.pastalab.fray.core.coroutines

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** Unit tests use a fake epoch executor; no JVMs or external services are launched. */
class CooperativeCliTest {
  @TempDir lateinit var temp: Path
  private val json = Json { encodeDefaults = true }

  private fun sha(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  private fun file(name: String, contents: String): Path {
    val path = temp.resolve(name)
    Files.createDirectories(path.parent)
    Files.writeString(path, contents)
    return path
  }

  private fun pin(path: Path) = CliPinnedFile(path.toString(), sha(Files.readAllBytes(path)))

  private fun config(
      out: String = "result",
      seeds: List<Long> = listOf(1, 2, 3),
  ): CliConfiguration {
    val java = file("jdk/bin/java", "java")
    java.toFile().setExecutable(true)
    val modules = file("jdk/lib/modules", "modules")
    val agent = file("agent.jar", "agent")
    val jvmti = file("jvmti.so", "native")
    val core = file("core.jar", "core")
    val stdlib = file("stdlib.jar", "stdlib")
    val coroutines = file("coroutines.jar", "coroutines")
    val app = file("app.jar", "app")
    val expected =
        ExpectedApplicationFailure(
            "java.lang.IllegalStateException",
            "example.Workload",
            "run",
            "lost",
        )
    return CliConfiguration(
        runtime =
            CliRuntime(
                java = pin(java),
                modules = pin(modules),
                agent = pin(agent),
                jvmti = pin(jvmti),
                jars = listOf(core, stdlib, coroutines).map(::pin),
                stdlibJar = stdlib.toString(),
                coroutineJar = coroutines.toString(),
            ),
        app =
            CliApplication(
                archive = pin(app),
                classes = mapOf("example/Workload" to sha("class".toByteArray())),
            ),
        entrypoint = Entrypoint("example.Workload", "run"),
        outputDirectory = temp.resolve(out).toString(),
        seeds = seeds,
        expectedFailure = expected,
    )
  }

  private fun configFile(config: CliConfiguration, filename: String = "config.json"): Path =
      file(filename, json.encodeToString(config))

  private data class CliCall(val code: Int, val stdout: String, val stderr: String) {
    fun result() = Json.parseToJsonElement(stdout.trim()).jsonObject
  }

  private fun call(
      config: CliConfiguration,
      executor: CliEpochExecutor,
      mode: String = "run",
      artifact: Path? = null,
  ): CliCall {
    val file = configFile(config)
    val args = mutableListOf(mode, "--config", file.toString())
    if (artifact != null) args.addAll(listOf("--artifact-dir", artifact.toString()))
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code =
        PrintStream(out).use { output ->
          PrintStream(err).use { errors ->
            CooperativeCliRunner(executor).run(args, output, errors)
          }
        }
    return CliCall(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
  }

  private data class ReplayFixture(val manifest: ReplayManifest, val directory: Path)

  private fun replayFixture(config: CliConfiguration): ReplayFixture {
    val dir = Files.createDirectory(temp.resolve("worker-recording"))
    val payloads =
        mapOf(
            "recording.json" to "[{\"example\":1}]",
            "random.json" to "{\"values\":[2]}",
            "strict-state-v1.json" to "{\"version\":1}",
        )
    payloads.forEach { (name, value) -> Files.writeString(dir.resolve(name), value) }
    val expected = requireNotNull(config.expectedFailure)
    val failure =
        FailureFingerprint(
            expected.className,
            "lost update",
            expected.topClass,
            expected.topMethod,
            "Workload.kt",
            42,
        )
    val manifest =
        ReplayManifest(
            schema = REPLAY_SCHEMA,
            entrypoint = config.entrypoint,
            seed = 2,
            classpathDigest = "a".repeat(64),
            childClasspathDigest = "b".repeat(64),
            appJarSha256 = config.app.archive.sha256,
            appClasses = config.app.classes,
            runtimeDigest = "c".repeat(64),
            processPolicyDigest = "d".repeat(64),
            scheduleDigest = sha(payloads.getValue("recording.json").toByteArray()),
            randomDigest = sha(payloads.getValue("random.json").toByteArray()),
            strictStateDigest = sha(payloads.getValue("strict-state-v1.json").toByteArray()),
            expectedFailure = expected,
            failure = failure,
        )
    return ReplayFixture(manifest, dir)
  }

  private fun bug(fixture: ReplayFixture, replay: Boolean = false): EpochOutcome {
    val response =
        EpochResponse(
            nonce = "nonce",
            status = EpochStatus.APPLICATION_BUG,
            failure = fixture.manifest.failure,
            manifest = fixture.manifest,
            evidence = EpochEvidence(strictReplayVerified = replay),
        )
    return EpochOutcome(EpochStatus.APPLICATION_BUG, "application bug", response, fixture.directory)
  }

  @Test
  fun iteratesSeedsAndStopsAtFirstUnsupportedOrInconclusive() {
    for ((blocked, exit) in listOf(EpochStatus.UNSUPPORTED to 20, EpochStatus.INCONCLUSIVE to 30)) {
      val cfg = config(out = "status-${blocked.name}")
      val seen = mutableListOf<Long>()
      val fake = CliEpochExecutor { _, seed, _, _, _, _, _ ->
        seen.add(seed)
        if (seed == 2L) EpochOutcome(blocked, "blocked")
        else EpochOutcome(EpochStatus.COMPLETED, "complete")
      }
      val result = call(cfg, fake)
      assertEquals(exit, result.code)
      assertEquals(listOf(1L, 2L), seen)
      assertEquals(blocked.name, result.result().getValue("status").jsonPrimitive.content)
      assertEquals(2, result.result().getValue("attempted").jsonPrimitive.int)
      assertEquals(1, result.result().getValue("completed").jsonPrimitive.int)
    }
  }

  @Test
  fun completeMeansEverySeedActuallyCompleted() {
    val cfg = config(out = "complete")
    val seen = mutableListOf<Long>()
    val result =
        call(
            cfg,
            CliEpochExecutor { _, seed, _, _, _, _, _ ->
              seen.add(seed)
              EpochOutcome(EpochStatus.COMPLETED, "complete")
            },
        )
    assertEquals(0, result.code)
    assertEquals(listOf(1L, 2L, 3L), seen)
    assertEquals("COMPLETED", result.result().getValue("status").jsonPrimitive.content)
    assertEquals(3, result.result().getValue("attempted").jsonPrimitive.int)
    assertEquals(3, result.result().getValue("completed").jsonPrimitive.int)
  }

  @Test
  fun campaignStopsBeforeNextSeedIfJdkImageChanges() {
    val cfg = config(out = "changed-image", seeds = listOf(1, 2))
    val native = file("jdk/lib/server/libjvm.so", "native-A")
    val seen = mutableListOf<Long>()
    val result =
        call(
            cfg,
            CliEpochExecutor { _, seed, runtime, _, _, _, _ ->
              assertTrue(runtime.expectedJdkImageDigest?.matches(Regex("[0-9a-f]{64}")) == true)
              seen.add(seed)
              if (seed == 1L) Files.writeString(native, "native-B")
              EpochOutcome(EpochStatus.COMPLETED, "complete")
            },
        )
    assertEquals(30, result.code)
    assertEquals("INCONCLUSIVE", result.result().getValue("status").jsonPrimitive.content)
    assertEquals(2, result.result().getValue("attempted").jsonPrimitive.int)
    assertEquals(1, result.result().getValue("completed").jsonPrimitive.int)
    assertEquals(listOf(1L), seen)
  }

  @Test
  fun invalidConfigAndExistingOutputNeverInvokeExecutorOrOverwrite() {
    var calls = 0
    val fake = CliEpochExecutor { _, _, _, _, _, _, _ ->
      calls++
      EpochOutcome(EpochStatus.COMPLETED, "complete")
    }
    val emptySeeds = config(out = "invalid-empty", seeds = emptyList())
    assertEquals(20, call(emptySeeds, fake).code)
    assertFalse(Files.exists(Path.of(emptySeeds.outputDirectory)))
    val duplicateSeeds = config(out = "invalid-duplicate", seeds = listOf(1, 1))
    assertEquals(20, call(duplicateSeeds, fake).code)
    assertFalse(Files.exists(Path.of(duplicateSeeds.outputDirectory)))
    val unknownSchema = config(out = "invalid-schema").copy(schema = 2)
    assertEquals(20, call(unknownSchema, fake).code)
    assertFalse(Files.exists(Path.of(unknownSchema.outputDirectory)))
    val badBase = config(out = "invalid-hash")
    val invalidHash =
        badBase.copy(
            runtime = badBase.runtime.copy(java = badBase.runtime.java.copy(sha256 = "bad"))
        )
    assertEquals(20, call(invalidHash, fake).code)
    assertFalse(Files.exists(Path.of(invalidHash.outputDirectory)))
    val target = Files.createDirectory(temp.resolve("existing-output"))
    val sentinel = Files.writeString(target.resolve("sentinel"), "untouched")
    val existing = config(out = "existing-output")
    assertTrue(call(existing, fake).code != 0)
    assertEquals("untouched", Files.readString(sentinel))
    assertEquals(0, calls)
  }

  @Test
  fun outputInsideJdkImageIsRejectedBeforeDirectoryCreationOrLaunch() {
    var calls = 0
    val fake = CliEpochExecutor { _, _, _, _, _, _, _ ->
      calls++
      EpochOutcome(EpochStatus.COMPLETED, "complete")
    }
    val cfg = config(out = "jdk/results")
    assertEquals(20, call(cfg, fake).code)
    assertFalse(Files.exists(Path.of(cfg.outputDirectory)))
    val alias = Files.createSymbolicLink(temp.resolve("jdk-alias"), temp.resolve("jdk"))
    val viaAlias = cfg.copy(outputDirectory = alias.resolve("results").toString())
    assertEquals(20, call(viaAlias, fake).code)
    assertFalse(Files.exists(Path.of(viaAlias.outputDirectory)))
    assertEquals(0, calls)
  }

  @Test
  fun outputSymlinkAndDuplicateOptionsAreRejected() {
    var calls = 0
    val fake = CliEpochExecutor { _, _, _, _, _, _, _ ->
      calls++
      EpochOutcome(EpochStatus.COMPLETED, "complete")
    }
    val real = Files.createDirectory(temp.resolve("real-output"))
    val sentinel = Files.writeString(real.resolve("sentinel"), "untouched")
    Files.createSymbolicLink(temp.resolve("output-link"), real)
    val cfg = config(out = "output-link")
    assertEquals(20, call(cfg, fake).code)
    assertEquals("untouched", Files.readString(sentinel))
    val configPath = configFile(cfg, "duplicate-config.json")
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit =
        PrintStream(out).use { output ->
          PrintStream(err).use { errors ->
            CooperativeCliRunner(fake)
                .run(
                    listOf(
                        "run",
                        "--config",
                        configPath.toString(),
                        "--config",
                        configPath.toString(),
                    ),
                    output,
                    errors,
                )
          }
        }
    assertEquals(20, exit)
    assertEquals(0, calls)
  }

  @Test
  fun legacyReplaySchemaIsRejectedBeforeOutputOrExecutor() {
    val cfg = config(out = "legacy-replay")
    val fixture = replayFixture(cfg)
    Files.writeString(
        fixture.directory.resolve("manifest.json"),
        json.encodeToString(fixture.manifest.copy(schema = 4)),
    )
    var calls = 0
    val result =
        call(
            cfg,
            CliEpochExecutor { _, _, _, _, _, _, _ ->
              calls++
              EpochOutcome(EpochStatus.COMPLETED, "complete")
            },
            "replay",
            fixture.directory,
        )
    assertEquals(20, result.code)
    assertEquals(0, calls)
    assertFalse(Files.exists(Path.of(cfg.outputDirectory)))
  }

  @Test
  fun replayWithoutStrictEvidenceCannotBecomeBugOutcome() {
    val cfg = config(out = "strict-replay")
    val fixture = replayFixture(cfg)
    Files.writeString(
        fixture.directory.resolve("manifest.json"),
        json.encodeToString(fixture.manifest),
    )
    val result =
        call(
            cfg,
            CliEpochExecutor { _, _, _, _, _, _, _ -> bug(fixture, replay = false) },
            mode = "replay",
            artifact = fixture.directory,
        )
    assertEquals(30, result.code)
    assertEquals("INCONCLUSIVE", result.result().getValue("status").jsonPrimitive.content)
    assertFalse(Files.exists(Path.of(cfg.outputDirectory).resolve("recording")))
  }

  @Test
  fun matchingFailureIsPersistedAndSourceArtifactsAreNotChanged() {
    val cfg = config(out = "failure")
    val fixture = replayFixture(cfg)
    val before =
        listOf("recording.json", "random.json", "strict-state-v1.json").associateWith {
          Files.readAllBytes(fixture.directory.resolve(it))
        }
    val seen = mutableListOf<Long>()
    val result =
        call(
            cfg,
            CliEpochExecutor { _, seed, _, _, _, _, _ ->
              seen.add(seed)
              if (seed == 2L) bug(fixture) else EpochOutcome(EpochStatus.COMPLETED, "complete")
            },
        )
    assertEquals(10, result.code)
    assertEquals(listOf(1L, 2L), seen)
    assertEquals("APPLICATION_BUG", result.result().getValue("status").jsonPrimitive.content)
    assertEquals(2, result.result().getValue("attempted").jsonPrimitive.int)
    assertEquals(1, result.result().getValue("completed").jsonPrimitive.int)
    val target = Path.of(cfg.outputDirectory).resolve("recording")
    before.forEach { (name, original) ->
      assertContentEquals(original, Files.readAllBytes(target.resolve(name)))
      assertContentEquals(original, Files.readAllBytes(fixture.directory.resolve(name)))
    }
    assertEquals(
        fixture.manifest,
        Json.decodeFromString<ReplayManifest>(Files.readString(target.resolve("manifest.json"))),
    )
  }

  @Test
  fun missingOrChangedArtifactCannotProduceBugOutcome() {
    val cfg = config(out = "bad-artifact")
    val fixture = replayFixture(cfg)
    Files.writeString(fixture.directory.resolve("random.json"), "changed")
    val result = call(cfg, CliEpochExecutor { _, _, _, _, _, _, _ -> bug(fixture) })
    assertEquals(30, result.code)
    assertEquals("INCONCLUSIVE", result.result().getValue("status").jsonPrimitive.content)
    assertEquals("changed", Files.readString(fixture.directory.resolve("random.json")))
  }

  @Test
  fun replayUsesRecordedSeedAndLeavesInputUntouched() {
    val cfg = config(out = "replay", seeds = listOf(99))
    val fixture = replayFixture(cfg)
    Files.writeString(
        fixture.directory.resolve("manifest.json"),
        json.encodeToString(fixture.manifest),
    )
    val names = listOf("recording.json", "random.json", "strict-state-v1.json", "manifest.json")
    val before = names.associateWith { Files.readAllBytes(fixture.directory.resolve(it)) }
    var seenReplay: ReplayInput? = null
    var seenSeed: Long? = null
    val result =
        call(
            cfg,
            CliEpochExecutor { _, seed, _, _, replay, expected, _ ->
              seenSeed = seed
              seenReplay = replay
              assertEquals(fixture.manifest.expectedFailure, expected)
              bug(fixture, replay = true)
            },
            mode = "replay",
            artifact = fixture.directory,
        )
    assertEquals(10, result.code)
    assertEquals(fixture.manifest.seed, seenSeed)
    assertEquals(fixture.manifest, assertNotNull(seenReplay).manifest)
    assertEquals("APPLICATION_BUG", result.result().getValue("status").jsonPrimitive.content)
    assertTrue(result.result().getValue("strictReplayVerified").jsonPrimitive.content.toBoolean())
    before.forEach { (name, original) ->
      assertContentEquals(original, Files.readAllBytes(fixture.directory.resolve(name)))
    }
  }
}
