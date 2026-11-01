package org.pastalab.fray.core.coroutines

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.core.command.MethodExecutor
import org.pastalab.fray.core.observers.ScheduleRecording

/** Opt-in lab probe; requires locally built runtime jars, agents and compiled fixture classes. */
@EnabledIfEnvironmentVariable(named = "FRAY_COOPERATIVE_INTEGRATION", matches = "true")
class CooperativeEpochIntegrationTest {
  @TempDir lateinit var temp: Path

  private fun property(name: String): String {
    val key = "FRAY_COOPERATIVE_" + name.uppercase().replace('.', '_')
    return System.getenv(key) ?: error("Missing $key")
  }

  private fun digest(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  private fun pin(path: Path) = PinnedFile(path, digest(Files.readAllBytes(path)))

  @Test
  fun realDefaultAndMutexCompleteInSeparateChildren() {
    val fixtureDir = Path.of(property("fixture.dir")).resolve("org/pastalab/fray/test/poc")
    val files =
        Files.list(fixtureDir).use { stream ->
          stream
              .filter {
                it.fileName.toString().startsWith("CooperativeDefaultFixture") &&
                    it.fileName.toString().endsWith(".class")
              }
              .sorted()
              .iterator()
              .asSequence()
              .toList()
        }
    assertEquals(7, files.size, "Fixture inventory changed; review the cooperative graph")
    val classes = linkedMapOf<String, String>()
    val app = temp.resolve("fixture-only.jar")
    ZipOutputStream(Files.newOutputStream(app)).use { zip ->
      for (file in files) {
        val name = "org/pastalab/fray/test/poc/" + file.fileName.toString().removeSuffix(".class")
        val contents = Files.readAllBytes(file)
        classes[name] = digest(contents)
        zip.putNextEntry(ZipEntry("$name.class"))
        zip.write(contents)
        zip.closeEntry()
      }
    }
    val java = Path.of(property("java")).toRealPath()
    val modules = java.parent.parent.resolve("lib/modules").toRealPath()
    val stdlib = Path.of(property("stdlib")).toRealPath()
    val coroutines = Path.of(property("coroutines")).toRealPath()
    val libraries = property("jars").split(File.pathSeparator).map { Path.of(it).toRealPath() }
    require(
        stdlib in libraries &&
            coroutines in libraries &&
            libraries.distinct().size == libraries.size
    )
    val jars = libraries + listOf(app.toRealPath())
    val runtime =
        EpochRuntimeSpec(
            pin(java),
            pin(modules),
            pin(Path.of(property("agent"))),
            pin(Path.of(property("jvmti"))),
            jars.map(::pin),
        )
    val inputs =
        CooperativeExecutionInputs(
            app.toString(),
            digest(Files.readAllBytes(app)),
            classes,
            stdlib.toString(),
            coroutines.toString(),
        )
    val backend = CoroutineEpochBackend(temp.resolve("epochs"))
    // Actual child negative: reproduce the old HostBridge.queue call in the app bytecode.
    // A null argument is enough to exercise admission; no external receiver is supplied.
    val badName = "adversarial/HostCallback"
    fun callbackBytes(includeHostCall: Boolean): ByteArray {
      val writer = ClassWriter(0)
      writer.visit(
          Opcodes.V11,
          Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
          badName,
          null,
          "java/lang/Object",
          null,
      )
      val method =
          writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
      method.visitCode()
      if (includeHostCall) {
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "org/pastalab/fray/poc/AdversarialCoroutineTest\$HostBridge",
            "queue",
            "(Ljava/lang/Runnable;)V",
            false,
        )
      }
      method.visitInsn(Opcodes.RETURN)
      method.visitMaxs(1, 0)
      method.visitEnd()
      writer.visitEnd()
      return writer.toByteArray()
    }
    val positiveBytes = callbackBytes(false)
    val positiveJar = temp.resolve("host-callback-positive.jar")
    ZipOutputStream(Files.newOutputStream(positiveJar)).use { zip ->
      zip.putNextEntry(ZipEntry("$badName.class"))
      zip.write(positiveBytes)
      zip.closeEntry()
    }
    val badEntry = Entrypoint("adversarial.HostCallback", "run")
    val positiveArchive =
        CooperativeApplicationArchive(
            positiveJar,
            digest(Files.readAllBytes(positiveJar)),
            mapOf(badName to digest(positiveBytes)),
        )
    assertEquals(
        setOf(badName),
        CooperativeApplicationArchiveReader.read(positiveArchive, badEntry).classNames(),
    )
    val badBytes = callbackBytes(true)
    val badApp = temp.resolve("host-callback.jar")
    ZipOutputStream(Files.newOutputStream(badApp)).use { zip ->
      zip.putNextEntry(ZipEntry("$badName.class"))
      zip.write(badBytes)
      zip.closeEntry()
    }
    val badClasses = mapOf(badName to digest(badBytes))
    val badArchive =
        CooperativeApplicationArchive(badApp, digest(Files.readAllBytes(badApp)), badClasses)
    val rejectedGraph =
        assertFailsWith<IllegalArgumentException> {
          CooperativeApplicationArchiveReader.read(badArchive, badEntry)
        }
    assertTrue(
        rejectedGraph.message
            .orEmpty()
            .contains(
                "unsupported call: org/pastalab/fray/poc/AdversarialCoroutineTest\$HostBridge.queue"
            )
    )
    val badJars = libraries + listOf(badApp.toRealPath())
    val badRuntime = runtime.copy(jars = badJars.map(::pin))
    val badInputs =
        CooperativeExecutionInputs(
            badApp.toString(),
            badArchive.sha256,
            badClasses,
            stdlib.toString(),
            coroutines.toString(),
        )
    val badExecutor =
        MethodExecutor(
            badEntry.className,
            badEntry.methodName,
            emptyList(),
            badJars.map(Path::toString),
            emptyMap(),
        )
    val rejected =
        backend.run(badExecutor, true, 74231, badRuntime, 60_000, cooperativeInputs = badInputs)
    assertEquals(EpochStatus.UNSUPPORTED, rejected.status, "host bridge: ${rejected.reason}")
    assertEquals("Cooperative app or runtime policy was rejected", rejected.reason)
    println("WORKER_NEGATIVE host-bridge=UNSUPPORTED; positive-control=accepted")
    // Non-entry continuation shadow must be rejected even though the entry itself is absent.
    val continuation = files.first { '$' in it.fileName.toString() }
    val shadowJar = temp.resolve("continuation-shadow.jar")
    ZipOutputStream(Files.newOutputStream(shadowJar)).use { zip ->
      zip.putNextEntry(ZipEntry("org/pastalab/fray/test/poc/" + continuation.fileName.toString()))
      zip.write(Files.readAllBytes(continuation))
      zip.closeEntry()
    }
    val shadowJars = libraries + listOf(shadowJar.toRealPath(), app.toRealPath())
    val shadowRuntime = runtime.copy(jars = shadowJars.map(::pin))
    val shadowExecutor =
        MethodExecutor(
            "org.pastalab.fray.test.poc.CooperativeDefaultFixture",
            "baseline",
            emptyList(),
            shadowJars.map(Path::toString),
            emptyMap(),
        )
    val shadowResult =
        backend.run(shadowExecutor, true, 74231, shadowRuntime, 60_000, cooperativeInputs = inputs)
    assertEquals(
        EpochStatus.UNSUPPORTED,
        shadowResult.status,
        "non-entry shadow: ${shadowResult.reason}",
    )
    assertTrue(
        Files.readString(requireNotNull(shadowResult.diagnosticLog))
            .contains("COOPERATIVE_PARENT_APP_SHADOW_REJECTED")
    )
    println("WORKER_NEGATIVE parent-continuation-shadow=UNSUPPORTED")
    val seeds = (System.getenv("FRAY_COOPERATIVE_SEEDS") ?: "74231").split(',').map { it.toLong() }
    require(seeds.isNotEmpty() && seeds.size <= 20 && seeds.distinct().size == seeds.size)
    for (seed in seeds) for (method in listOf("baseline", "mutex")) {
      val executor =
          MethodExecutor(
              "org.pastalab.fray.test.poc.CooperativeDefaultFixture",
              method,
              emptyList(),
              jars.map(Path::toString),
              emptyMap(),
          )
      val result = backend.run(executor, true, seed, runtime, 60_000, cooperativeInputs = inputs)
      val log =
          result.diagnosticLog?.takeIf { Files.exists(it) }?.let(Files::readString)
              ?: "No bounded worker log"
      assertEquals(
          EpochStatus.COMPLETED,
          result.status,
          "$method seed=$seed: ${result.reason}; bounded local worker log:\n$log",
      )
      assertTrue(
          log.contains("COOPERATIVE_PARENT_APP_GRAPH_INVISIBLE"),
          "parent loader visibility check missing",
      )
      val e = requireNotNull(result.response).evidence
      assertTrue(
          e.admissionVerified &&
              e.workloadTerminated &&
              e.jobBoundaryVerified &&
              e.callbacksDrained &&
              e.teardownComplete &&
              e.noUncaughtFailure
      )
      println("WORKER_COMPLETED $method seed=$seed workload=1 boundary=1 teardown=1 helper=stopped")
    }
    fun cliConfiguration(
        method: String,
        output: String,
        selectedSeeds: List<Long>,
        expectedFailure: ExpectedApplicationFailure? = null,
    ): CliConfiguration {
      fun cliPin(file: PinnedFile) = CliPinnedFile(file.path.toString(), file.sha256)
      return CliConfiguration(
          runtime =
              CliRuntime(
                  cliPin(runtime.java),
                  cliPin(runtime.modules),
                  cliPin(runtime.agent),
                  cliPin(runtime.jvmti),
                  runtime.jars.dropLast(1).map(::cliPin),
                  stdlib.toString(),
                  coroutines.toString(),
                  runtime.maxHeapMb,
                  runtime.activeProcessors,
              ),
          app =
              CliApplication(CliPinnedFile(app.toString(), inputs.appJarSha256), inputs.appClasses),
          entrypoint = Entrypoint("org.pastalab.fray.test.poc.CooperativeDefaultFixture", method),
          outputDirectory = temp.resolve(output).toString(),
          timeoutMillis = 60_000,
          seeds = selectedSeeds,
          expectedFailure = expectedFailure,
      )
    }
    fun invokeCli(args: List<String>): Pair<Int, String> {
      val log = Files.createTempFile(temp, "cli-process-", ".log")
      val command =
          listOf(
              java.toString(),
              "-Xmx384m",
              "-XX:ActiveProcessorCount=4",
              "-cp",
              libraries.joinToString(File.pathSeparator),
              CooperativeCli::class.java.name,
          ) + args
      val process = ProcessBuilder(command).redirectErrorStream(true).start()
      fun stopProcessTree() {
        runCatching {
          process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        }
        process.destroyForcibly()
      }
      val overflow = AtomicBoolean(false)
      val readFailure = AtomicBoolean(false)
      val reader = Thread {
        try {
          process.inputStream.use { input ->
            Files.newOutputStream(log).use { output ->
              val buffer = ByteArray(4096)
              var total = 0
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val accepted = minOf(count, 256 * 1024 - total)
                if (accepted > 0) output.write(buffer, 0, accepted)
                total += accepted
                if (accepted != count) {
                  overflow.set(true)
                  stopProcessTree()
                  break
                }
              }
            }
          }
        } catch (_: Exception) {
          readFailure.set(true)
        }
      }
      reader.isDaemon = true
      reader.start()
      val completed = process.waitFor(90, TimeUnit.SECONDS)
      if (!completed) {
        stopProcessTree()
        process.waitFor(2, TimeUnit.SECONDS)
      }
      reader.join(2_000)
      val output = Files.readString(log)
      assertTrue(completed, "CLI process exceeded 90-second watchdog: $output")
      assertTrue(
          !reader.isAlive && !overflow.get() && !readFailure.get(),
          "CLI output exceeded limits or failed: $output",
      )
      return process.exitValue() to output
    }
    if (System.getenv("FRAY_COOPERATIVE_CLI") == "true") {
      val cliConfig = temp.resolve("cli-run-config.json")
      Files.writeString(
          cliConfig,
          Json.encodeToString(
              cliConfiguration("baseline", "cli-run-output", listOf(seeds.first()))
          ),
      )
      val (status, output) = invokeCli(listOf("run", "--config", cliConfig.toString()))
      assertEquals(0, status, "CLI baseline: $output")
      assertTrue(output.contains("\"status\":\"COMPLETED\""), output)
      println("COOPERATIVE_CLI_BASELINE_COMPLETED")
    }
    if (System.getenv("FRAY_COOPERATIVE_REPLAY") == "true") {
      val expected =
          ExpectedApplicationFailure(
              "java.lang.IllegalStateException",
              "org.pastalab.fray.test.poc.CooperativeDefaultFixture\$race\$1",
              "invokeSuspend",
              "lost update",
          )
      val raceExecutor =
          MethodExecutor(
              "org.pastalab.fray.test.poc.CooperativeDefaultFixture",
              "race",
              emptyList(),
              jars.map(Path::toString),
              emptyMap(),
          )
      val raceSeeds =
          (System.getenv("FRAY_COOPERATIVE_RACE_SEEDS") ?: (74231..74240).joinToString(","))
              .split(',')
              .map { it.toLong() }
      require(
          raceSeeds.isNotEmpty() &&
              raceSeeds.size <= 20 &&
              raceSeeds.distinct().size == raceSeeds.size
      )
      var recorded: EpochOutcome? = null
      var foundSeed = 0L
      for (seed in raceSeeds) {
        val attempt =
            backend.run(
                raceExecutor,
                true,
                seed,
                runtime,
                60_000,
                expectedFailure = expected,
                cooperativeInputs = inputs,
            )
        val log =
            attempt.diagnosticLog?.takeIf { Files.exists(it) }?.let(Files::readString)
                ?: "No worker log"
        println("WORKER_RACE seed=$seed status=${attempt.status}")
        if (attempt.status == EpochStatus.APPLICATION_BUG) {
          recorded = attempt
          foundSeed = seed
          break
        }
        assertEquals(
            EpochStatus.COMPLETED,
            attempt.status,
            "race seed=$seed: ${attempt.reason}\n$log",
        )
      }
      val found = requireNotNull(recorded) { "Controlled race was not found in supplied seeds" }
      val directory = requireNotNull(found.artifactDirectory)
      val manifest = requireNotNull(found.response?.manifest)
      val schedule = directory.resolve("recording.json")
      val random = directory.resolve("random.json")
      val strict = directory.resolve("strict-state-v1.json")
      val originals = listOf(schedule, random, strict)
      val original = originals.map { digest(Files.readAllBytes(it)) }
      var evidenceReadme: Path? = null
      val exported =
          System.getenv("FRAY_COOPERATIVE_EVIDENCE_DIR")?.let { requested ->
            require(requested.isNotBlank()) { "Evidence path must be nonempty" }
            val evidence = Path.of(requested).toAbsolutePath().normalize()
            if (!Files.exists(evidence, LinkOption.NOFOLLOW_LINKS))
                Files.createDirectories(evidence)
            require(
                !Files.isSymbolicLink(evidence) &&
                    Files.isDirectory(evidence, LinkOption.NOFOLLOW_LINKS)
            )
            require(Files.list(evidence).use { !it.findAny().isPresent }) {
              "Evidence directory must be empty"
            }
            val copies =
                originals.map { source -> Files.copy(source, evidence.resolve(source.fileName)) }
            val manifestPath = evidence.resolve("manifest.json")
            Files.writeString(manifestPath, Json { encodeDefaults = true }.encodeToString(manifest))
            evidenceReadme = evidence.resolve("README.md")
            Files.writeString(
                evidenceReadme,
                "# Local coroutine race evidence\n\nSeed: $foundSeed\n\nDiscovery: APPLICATION_BUG\n\nReplay: pending\n",
            )
            copies + listOf(manifestPath)
          } ?: emptyList()
      val exportedDigests = exported.map { digest(Files.readAllBytes(it)) }
      val replayCount = (System.getenv("FRAY_COOPERATIVE_REPLAY_COUNT") ?: "1").toInt()
      require(replayCount in 1..5) { "FRAY_COOPERATIVE_REPLAY_COUNT must be 1..5" }
      val childEpochs = mutableSetOf<Path>()
      repeat(replayCount) { iteration ->
        val replayed =
            backend.run(
                raceExecutor,
                true,
                foundSeed,
                runtime,
                60_000,
                replay = ReplayInput(manifest, schedule, random, strict),
                expectedFailure = expected,
                cooperativeInputs = inputs,
            )
        val replayLog =
            replayed.diagnosticLog?.takeIf { Files.exists(it) }?.let(Files::readString)
                ?: "No worker log"
        evidenceReadme?.let { readme ->
          Files.writeString(
              readme,
              "# Local coroutine race evidence\n\nSeed: $foundSeed\n\nDiscovery: APPLICATION_BUG\n\nReplay ${iteration + 1}/$replayCount: ${replayed.status}; strict verified: ${replayed.response?.evidence?.strictReplayVerified == true}\n",
          )
        }
        assertEquals(
            exportedDigests,
            exported.map { digest(Files.readAllBytes(it)) },
            "exported evidence changed during replay",
        )
        assertEquals(
            original,
            originals.map { digest(Files.readAllBytes(it)) },
            "original evidence changed during replay",
        )
        assertEquals(
            EpochStatus.APPLICATION_BUG,
            replayed.status,
            "replay ${iteration + 1}: ${replayed.reason}\n$replayLog",
        )
        assertTrue(requireNotNull(replayed.response).evidence.strictReplayVerified)
        assertEquals(manifest, replayed.response?.manifest)
        assertTrue(
            childEpochs.add(requireNotNull(replayed.diagnosticLog).parent),
            "replay child epoch was reused",
        )
        println(
            "WORKER_RACE_REPLAYED iteration=${iteration + 1} seed=$foundSeed strict-state=matched int-stream=consumed artifacts=unchanged"
        )
      }
      if (System.getenv("FRAY_COOPERATIVE_CLI") == "true") {
        // Invoke the documented public main in separate JVMs: discover, persist, replay, and
        // reject reuse of the discovery output without changing any recorded input artifact.
        val discoveryConfig = temp.resolve("cli-discovery-config.json")
        Files.writeString(
            discoveryConfig,
            Json.encodeToString(
                cliConfiguration("race", "cli-discovery-output", listOf(foundSeed), expected)
            ),
        )
        val (discoveryCode, discoveryOutput) =
            invokeCli(listOf("run", "--config", discoveryConfig.toString()))
        assertEquals(10, discoveryCode, "CLI discovery: $discoveryOutput")
        assertTrue(discoveryOutput.contains("\"status\":\"APPLICATION_BUG\""), discoveryOutput)
        val cliInput = temp.resolve("cli-discovery-output/recording")
        val cliFiles =
            listOf("recording.json", "random.json", "strict-state-v1.json", "manifest.json").map {
              cliInput.resolve(it)
            }
        val cliBefore = cliFiles.map { digest(Files.readAllBytes(it)) }
        val (duplicateCode, duplicateOutput) =
            invokeCli(listOf("run", "--config", discoveryConfig.toString()))
        assertEquals(20, duplicateCode, "CLI output reuse: $duplicateOutput")
        assertEquals(cliBefore, cliFiles.map { digest(Files.readAllBytes(it)) })
        val cliConfig = temp.resolve("cli-replay-config.json")
        Files.writeString(
            cliConfig,
            Json.encodeToString(
                cliConfiguration("race", "cli-replay-output", listOf(foundSeed), expected)
            ),
        )
        val (status, output) =
            invokeCli(
                listOf(
                    "replay",
                    "--config",
                    cliConfig.toString(),
                    "--artifact-dir",
                    cliInput.toString(),
                )
            )
        assertEquals(10, status, "CLI strict replay: $output")
        assertTrue(output.contains("\"strictReplayVerified\":true"), output)
        assertEquals(cliBefore, cliFiles.map { digest(Files.readAllBytes(it)) })
        assertEquals(original, originals.map { digest(Files.readAllBytes(it)) })
        println("COOPERATIVE_CLI_DISCOVERY_REPLAY_CONFIRMED no-overwrite=true")
      }
      val scheduleEntries =
          Json.decodeFromString<List<ScheduleRecording>>(Files.readString(schedule))
      require(scheduleEntries.size > 1) { "Need a multi-step recording to test truncated trace" }
      val truncatedJson = Json.encodeToString(scheduleEntries.dropLast(1))
      val truncated = temp.resolve("truncated-schedule.json")
      Files.writeString(truncated, truncatedJson)
      val badManifest = manifest.copy(scheduleDigest = digest(truncatedJson.toByteArray()))
      val crossEpoch = Files.createDirectory(temp.resolve("cross-trace-control"))
      val crossRecording = Files.createDirectory(crossEpoch.resolve("recording"))
      Files.copy(truncated, crossRecording.resolve("recording.json"))
      Files.copy(random, crossRecording.resolve("random.json"))
      Files.copy(strict, crossRecording.resolve("strict-state-v1.json"))
      assertTrue(
          !backend.verifyArtifacts(
              crossEpoch,
              badManifest,
              System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
          ),
          "truncated schedule must fail strict cross-trace validation",
      )
      val truncatedResult =
          backend.run(
              raceExecutor,
              true,
              foundSeed,
              runtime,
              60_000,
              replay = ReplayInput(badManifest, truncated, random, strict),
              expectedFailure = expected,
              cooperativeInputs = inputs,
          )
      assertEquals(
          EpochStatus.INCONCLUSIVE,
          truncatedResult.status,
          "truncated trace must fail before replay",
      )
      assertTrue(
          truncatedResult.diagnosticLog == null,
          "invalid input must fail before child launch",
      )
      println("WORKER_REPLAY_TRUNCATED_CROSS_TRACE_REJECTED status=INCONCLUSIVE child=not-launched")
    }
  }
}
