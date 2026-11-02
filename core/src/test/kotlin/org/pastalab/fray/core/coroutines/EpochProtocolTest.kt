package org.pastalab.fray.core.coroutines

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import org.pastalab.fray.core.command.LambdaExecutor
import org.pastalab.fray.core.command.MethodExecutor
import org.pastalab.fray.core.observers.STRICT_STATE_V1_FILE
import org.pastalab.fray.core.observers.ScheduleRecording
import org.pastalab.fray.core.observers.StrictFailureV1
import org.pastalab.fray.core.observers.StrictScheduleStepV1
import org.pastalab.fray.core.observers.StrictStateTraceV1
import org.pastalab.fray.core.observers.StrictThreadStateV1
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.Randomness

class EpochProtocolTest {
  @TempDir lateinit var temp: Path
  private val shaA = "a".repeat(64)
  private val shaB = "b".repeat(64)
  private val shaC = "c".repeat(64)
  private val entry = Entrypoint("example.Workload", "run")
  private val inputs =
      CooperativeExecutionInputs(
          "/app.jar",
          shaA,
          mapOf("example/Workload" to shaB, "example/Helper" to shaC),
          "/stdlib.jar",
          "/coroutines.jar",
      )
  private val expected =
      ExpectedApplicationFailure(
          "java.lang.IllegalStateException",
          "example.Workload",
          "run",
          "lost update",
      )
  private val request =
      EpochRequest(
          nonce = "nonce",
          entrypoint = entry,
          seed = 1,
          classpathDigest = shaA,
          childClasspathDigest = shaB,
          runtimeDigest = shaB,
          processPolicyDigest = shaC,
          expectedFailure = expected,
          cooperativeInputs = inputs,
      )
  private val complete =
      EpochEvidence(
          admissionVerified = true,
          workloadTerminated = true,
          jobBoundaryVerified = true,
          callbacksDrained = true,
          teardownComplete = true,
          noUncaughtFailure = true,
      )
  private val failure =
      FailureFingerprint(
          "java.lang.IllegalStateException",
          "lost update",
          "example.Workload",
          "run",
          "Workload.kt",
          42,
      )

  private fun manifest() =
      ReplayManifest(
          schema = REPLAY_SCHEMA,
          entrypoint = entry,
          seed = 1,
          classpathDigest = shaA,
          childClasspathDigest = shaB,
          appJarSha256 = shaA,
          appClasses = inputs.appClasses,
          runtimeDigest = shaB,
          processPolicyDigest = shaC,
          scheduleDigest = shaA,
          randomDigest = shaB,
          strictStateDigest = shaC,
          expectedFailure = expected,
          failure = failure,
      )

  @Test
  fun completionRequiresEveryProofAndMatchingExitAndNonce() {
    val good =
        EpochResponse(nonce = request.nonce, status = EpochStatus.COMPLETED, evidence = complete)
    assertEquals(EpochStatus.COMPLETED, EpochProtocolValidator.validate(request, good, 0).status)
    for (bad in
        listOf(
            good.copy(nonce = "different"),
            good.copy(evidence = complete.copy(admissionVerified = false)),
            good.copy(evidence = complete.copy(workloadTerminated = false)),
            good.copy(evidence = complete.copy(jobBoundaryVerified = false)),
            good.copy(evidence = complete.copy(callbacksDrained = false)),
            good.copy(evidence = complete.copy(teardownComplete = false)),
            good.copy(evidence = complete.copy(noUncaughtFailure = false)),
            good.copy(failure = failure),
        )) assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request, bad, 0).status,
    )
    assertEquals(EpochStatus.INCONCLUSIVE, EpochProtocolValidator.validate(request, good, 1).status)
    for (nonce in listOf("", "   ", "x".repeat(129))) {
      assertEquals(
          EpochStatus.INCONCLUSIVE,
          EpochProtocolValidator.validate(request.copy(nonce = nonce), good.copy(nonce = nonce), 0)
              .status,
      )
    }
  }

  @Test
  fun bugRequiresCompleteBoundManifestAndFreshStrictReplay() {
    val bug =
        EpochResponse(
            nonce = request.nonce,
            status = EpochStatus.APPLICATION_BUG,
            evidence = complete,
            failure = failure,
            manifest = manifest(),
        )
    assertEquals(
        EpochStatus.APPLICATION_BUG,
        EpochProtocolValidator.validate(request, bug, 10).status,
    )
    // These identity fields bind the actual child classpath and app's complete class inventory.
    for (changed in
        listOf(
            manifest().copy(schema = 3),
            manifest().copy(schema = 4),
            manifest().copy(childClasspathDigest = shaA),
            manifest().copy(appJarSha256 = shaB),
            manifest().copy(appClasses = mapOf("example/Workload" to shaB)),
            manifest().copy(appClasses = inputs.appClasses + ("example/Extra" to shaA)),
            manifest().copy(appClasses = inputs.appClasses + ("example/Helper" to shaA)),
            manifest().copy(appClasses = inputs.appClasses + ("example/Invalid" to "bad")),
            manifest().copy(appClasses = inputs.appClasses + ("../bad" to shaA)),
        )) {
      assertEquals(
          EpochStatus.INCONCLUSIVE,
          EpochProtocolValidator.validate(request, bug.copy(manifest = changed), 10).status,
      )
    }
    val noSchema = Json.encodeToString(manifest()).replaceFirst("\"schema\":5,", "")
    assertFails { Json.decodeFromString<ReplayManifest>(noSchema) }
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request.copy(cooperativeInputs = null), bug, 10).status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request.copy(childClasspathDigest = shaA), bug, 10).status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request.copy(cooperativeInputs = inputs.copy(appJarSha256 = shaB)),
                bug,
                10,
            )
            .status,
    )
    // Map ordering is immaterial; contents and hashes must match exactly.
    val sameInventory = inputs.appClasses.entries.reversed().associate { it.key to it.value }
    assertEquals(
        EpochStatus.APPLICATION_BUG,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(appClasses = sameInventory)),
                10,
            )
            .status,
    )
    // A response cannot classify a bug without the caller's explicit matching expectation.
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request.copy(expectedFailure = null), bug, 10).status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request.copy(expectedFailure = expected.copy(messagePrefix = " ")),
                bug,
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request.copy(expectedFailure = expected.copy(topMethod = "other")),
                bug,
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(
                    manifest =
                        manifest().copy(expectedFailure = expected.copy(messagePrefix = "lost"))
                ),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(schema = 2)),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request, bug.copy(manifest = null), 10).status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(scheduleDigest = "bad")),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(failure = failure.copy(message = "different"))),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(strictStateDigest = "bad")),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(strictStateVersion = 2)),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(schema = 1)),
                10,
            )
            .status,
    )
    val internalFailure = failure.copy(className = "org.pastalab.fray.runtime.LivenessException")
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(
                    failure = internalFailure,
                    manifest = manifest().copy(failure = internalFailure),
                ),
                10,
            )
            .status,
    )
    val noSource = failure.copy(topLine = -1)
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(failure = noSource, manifest = manifest().copy(failure = noSource)),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request, bug.copy(manifest = manifest().copy(seed = 2)), 10)
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                request,
                bug.copy(manifest = manifest().copy(processPolicyDigest = shaA)),
                10,
            )
            .status,
    )
    val replayRequest = request.copy(replay = true, replayManifest = manifest())
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(replayRequest, bug, 10).status,
    )
    val replay = bug.copy(evidence = complete.copy(strictReplayVerified = true))
    assertEquals(
        EpochStatus.APPLICATION_BUG,
        EpochProtocolValidator.validate(replayRequest, replay, 10).status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                replayRequest,
                replay.copy(manifest = manifest().copy(randomDigest = shaA)),
                10,
            )
            .status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(
                replayRequest,
                replay.copy(manifest = manifest().copy(strictStateDigest = shaA)),
                10,
            )
            .status,
    )
  }

  @Test
  fun unsupportedAndInconclusiveNeverBecomeSuccess() {
    val unsupported =
        EpochResponse(
            nonce = request.nonce,
            status = EpochStatus.UNSUPPORTED,
            reason = "unsupported",
        )
    assertEquals(
        EpochStatus.UNSUPPORTED,
        EpochProtocolValidator.validate(request, unsupported, 20).status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request, unsupported.copy(reason = null), 20).status,
    )
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request, unsupported, 0).status,
    )
    val inconclusive =
        EpochResponse(nonce = request.nonce, status = EpochStatus.INCONCLUSIVE, reason = "deadline")
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        EpochProtocolValidator.validate(request, inconclusive, 30).status,
    )
  }

  @Test
  fun unsupportedExecutorsAreRejectedBeforeRuntimeOrFilesystemAccess() {
    val missing = PinnedFile(temp.resolve("missing"), shaA)
    val spec = EpochRuntimeSpec(missing, missing, missing, missing, listOf(missing))
    val backend = CoroutineEpochBackend(temp.resolve("not-created"))
    assertEquals(
        EpochStatus.UNSUPPORTED,
        backend.run(LambdaExecutor {}, true, 1, spec, 1_000).status,
    )
    val method =
        MethodExecutor("example.Workload", "run", emptyList(), listOf("missing.jar"), emptyMap())
    assertEquals(EpochStatus.UNSUPPORTED, backend.run(method, false, 1, spec, 1_000).status)
    assertEquals(
        EpochStatus.UNSUPPORTED,
        backend.run(method.copy(args = listOf("arg")), true, 1, spec, 1_000).status,
    )
    assertEquals(
        EpochStatus.UNSUPPORTED,
        backend.run(method.copy(properties = mapOf("x" to "y")), true, 1, spec, 1_000).status,
    )
    assertEquals(EpochStatus.UNSUPPORTED, backend.run(method, true, 1, spec, 0).status)
    assertEquals(
        EpochStatus.UNSUPPORTED,
        backend
            .run(method, true, 1, spec, 1_000, expectedFailure = expected.copy(messagePrefix = ""))
            .status,
    )
    val missingReplay =
        ReplayInput(
            manifest(),
            temp.resolve("schedule"),
            temp.resolve("random"),
            temp.resolve("state"),
        )
    assertEquals(
        EpochStatus.UNSUPPORTED,
        backend.run(method, true, 1, spec, 1_000, replay = missingReplay).status,
    )
    assertFalse(Files.exists(temp.resolve("not-created")))
    assertTrue("-XX:+DisableAttachMechanism" in CoroutineEpochBackend.FIXED_JVM_FLAGS)
    assertFalse(backend.processPolicyDigest(384, 4) == backend.processPolicyDigest(384, 2))
    assertFalse(backend.processPolicyDigest(384, 4) == backend.processPolicyDigest(512, 4))
    assertFalse(
        backend.processPolicyDigest(384, 4, mapOf("TZ" to "UTC")) ==
            backend.processPolicyDigest(384, 4, mapOf("TZ" to "Europe/London"))
    )
  }

  @Test
  fun changedJdkNativeLibraryRejectsReplayBeforeChildLaunch() {
    fun write(name: String, contents: String): Path {
      val path = temp.resolve(name)
      Files.createDirectories(path.parent)
      return Files.writeString(path, contents)
    }
    fun pin(path: Path): PinnedFile =
        PinnedFile(
            path,
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
              "%02x".format(it.toInt() and 0xff)
            },
        )
    fun jar(name: String, worker: Boolean = false): Path {
      val path = temp.resolve(name)
      JarOutputStream(Files.newOutputStream(path)).use { out ->
        if (worker) {
          out.putNextEntry(JarEntry("org/pastalab/fray/core/coroutines/CoroutineEpochWorker.class"))
          out.write(1)
          out.closeEntry()
        }
      }
      return path
    }

    val marker = temp.resolve("child-was-launched")
    val java = write("jdk/bin/java", "#!/bin/sh\nprintf 'started' > '$marker'\n")
    assertTrue(java.toFile().setExecutable(true))
    val modules = write("jdk/lib/modules", "modules")
    val native = write("jdk/lib/server/libjvm.so", "native-A")
    val agent = write("agent.jar", "agent")
    val jvmti = write("jvmti.so", "jvmti")
    val worker = jar("worker.jar", true)
    val stdlib = jar("stdlib.jar")
    val coroutine = jar("coroutines.jar")
    val app = jar("app.jar")
    val spec =
        EpochRuntimeSpec(
            pin(java),
            pin(modules),
            pin(agent),
            pin(jvmti),
            listOf(worker, stdlib, coroutine, app).map(::pin),
        )
    val method =
        MethodExecutor(
            entry.className,
            entry.methodName,
            emptyList(),
            spec.jars.map { it.path.toString() },
            emptyMap(),
        )
    val appInputs =
        CooperativeExecutionInputs(
            app.toString(),
            spec.jars.last().sha256,
            inputs.appClasses,
            stdlib.toString(),
            coroutine.toString(),
        )
    val probe = CoroutineEpochBackend(temp.resolve("before"))
    val imageDigest =
        JdkImageFingerprint.digest(
            java.parent.parent,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
        )
    val environment =
        System.getenv().filterKeys {
          it in setOf("LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "SYSTEMROOT", "WINDIR")
        }
    val recording =
        manifest()
            .copy(
                classpathDigest = probe.digestOf(spec.jars.map { it.sha256 }),
                childClasspathDigest = probe.digestOf(spec.jars.dropLast(1).map { it.sha256 }),
                appJarSha256 = appInputs.appJarSha256,
                appClasses = appInputs.appClasses,
                runtimeDigest = probe.runtimeDigest(spec, imageDigest),
                processPolicyDigest =
                    probe.processPolicyDigest(spec.maxHeapMb, spec.activeProcessors, environment),
            )
    val replay =
        ReplayInput(
            recording,
            temp.resolve("missing-schedule"),
            temp.resolve("missing-random"),
            temp.resolve("missing-state"),
        )
    // Valid identity gets as far as making the recording directory, then stops on missing files.
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        probe.run(method, true, 1, spec, 30_000, replay, expected, appInputs).status,
    )
    Files.list(temp.resolve("before")).use { dirs ->
      assertTrue(dirs.anyMatch { Files.isDirectory(it.resolve("recording")) })
    }
    assertFalse(Files.exists(marker))

    val legacy = CoroutineEpochBackend(temp.resolve("legacy"))
    assertEquals(
        EpochStatus.UNSUPPORTED,
        legacy
            .run(
                method,
                true,
                1,
                spec,
                30_000,
                replay.copy(manifest = recording.copy(schema = 4)),
                expected,
                appInputs,
            )
            .status,
    )
    assertFalse(Files.exists(temp.resolve("legacy")))
    assertFalse(Files.exists(marker))

    // The launcher and modules remain byte-for-byte unchanged; the different libjvm must reject
    // the old manifest before artifact copying and before invoking the fake launcher.
    Files.writeString(native, "native-B")
    val after = CoroutineEpochBackend(temp.resolve("after"))
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        after.run(method, true, 1, spec, 30_000, replay, expected, appInputs).status,
    )
    Files.list(temp.resolve("after")).use { dirs ->
      assertFalse(dirs.anyMatch { Files.exists(it.resolve("recording")) })
    }
    assertFalse(Files.exists(marker))

    // A child which changes a non-launcher JDK component must be rejected by the post-run scan,
    // before an absent/malformed response can be treated as its only failure.
    val childMarker = temp.resolve("mutator-ran")
    Files.writeString(
        java,
        "#!/bin/sh\nprintf 'native-C' > '$native'\nprintf 'yes' > '$childMarker'\n",
    )
    assertTrue(java.toFile().setExecutable(true))
    val mutatingSpec = spec.copy(java = pin(java))
    val postRun = CoroutineEpochBackend(temp.resolve("post-run"))
    val outcome = postRun.run(method, true, 1, mutatingSpec, 30_000)
    assertTrue(Files.exists(childMarker))
    assertEquals(EpochStatus.INCONCLUSIVE, outcome.status)
    assertEquals("Pinned runtime changed or could not be verified", outcome.reason)
  }

  @Test
  fun artifactHashesStructureAndSymlinksAreChecked() {
    val recording = Files.createDirectory(temp.resolve("recording"))
    val tracePath = recording.resolve("recording.json")
    val randomPath = recording.resolve("random.json")
    val strictPath = recording.resolve(STRICT_STATE_V1_FILE)
    val trace = Json.encodeToString(listOf(ScheduleRecording(0, listOf(0), "example.Operation")))
    val random: Randomness = ControlledRandom(mutableListOf(2, 3), mutableListOf())
    val randomJson = Json.encodeToString<Randomness>(random)
    val strict =
        StrictStateTraceV1(
            1,
            StrictFailureV1(
                failure.className,
                failure.message,
                failure.topClass,
                failure.topMethod,
                failure.topFile,
                failure.topLine,
            ),
            listOf(
                StrictScheduleStepV1(
                    0,
                    listOf(0),
                    listOf(StrictThreadStateV1(0, "Runnable", "example.Operation", true, 42)),
                )
            ),
        )
    val strictJson = Json.encodeToString(strict)
    fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    Files.writeString(tracePath, trace)
    Files.writeString(randomPath, randomJson)
    Files.writeString(strictPath, strictJson)
    val valid =
        manifest()
            .copy(
                scheduleDigest = digest(trace),
                randomDigest = digest(randomJson),
                strictStateDigest = digest(strictJson),
            )
    val backend = CoroutineEpochBackend(temp)
    assertTrue(backend.verifyArtifacts(temp, valid, deadline()))
    Files.delete(strictPath)
    assertFalse(backend.verifyArtifacts(temp, valid, deadline()))
    Files.writeString(strictPath, "{}")
    assertFalse(backend.verifyArtifacts(temp, valid, deadline()))
    assertFalse(
        backend.verifyArtifacts(temp, valid.copy(strictStateDigest = digest("{}")), deadline())
    )
    val wrongVersion = Json.encodeToString(strict.copy(version = 2))
    Files.writeString(strictPath, wrongVersion)
    assertFalse(
        backend.verifyArtifacts(
            temp,
            valid.copy(strictStateDigest = digest(wrongVersion)),
            deadline(),
        )
    )
    val wrongFailure =
        Json.encodeToString(strict.copy(failure = strict.failure.copy(message = "different")))
    Files.writeString(strictPath, wrongFailure)
    assertFalse(
        backend.verifyArtifacts(
            temp,
            valid.copy(strictStateDigest = digest(wrongFailure)),
            deadline(),
        )
    )
    val noWorker = Json.encodeToString(strict.copy(steps = emptyList()))
    Files.writeString(strictPath, noWorker)
    assertFalse(
        backend.verifyArtifacts(temp, valid.copy(strictStateDigest = digest(noWorker)), deadline())
    )
    Files.delete(strictPath)
    Files.createSymbolicLink(strictPath, tracePath)
    assertFalse(backend.verifyArtifacts(temp, valid, deadline()))
    Files.delete(strictPath)
    Files.writeString(strictPath, strictJson)
    Files.writeString(tracePath, "[]")
    assertFalse(backend.verifyArtifacts(temp, valid, deadline()))
    assertFalse(
        backend.verifyArtifacts(temp, valid.copy(scheduleDigest = digest("[]")), deadline())
    )
    Files.writeString(tracePath, trace)
    val randomWithDouble: Randomness = ControlledRandom(mutableListOf(2), mutableListOf(0.5))
    val doubleJson = Json.encodeToString<Randomness>(randomWithDouble)
    Files.writeString(randomPath, doubleJson)
    assertFalse(
        backend.verifyArtifacts(temp, valid.copy(randomDigest = digest(doubleJson)), deadline())
    )
    Files.delete(randomPath)
    Files.createSymbolicLink(randomPath, tracePath)
    assertFalse(backend.verifyArtifacts(temp, valid, deadline()))
  }

  @Test
  fun legacyAndStrictTracesMustDescribeTheSameSteps() {
    val directory = Files.createDirectory(temp.resolve("recording"))
    val legacyPath = directory.resolve("recording.json")
    val strictPath = directory.resolve(STRICT_STATE_V1_FILE)
    val randomPath = directory.resolve("random.json")
    val random: Randomness = ControlledRandom(mutableListOf(2), mutableListOf())
    val randomJson = Json.encodeToString<Randomness>(random)
    Files.writeString(randomPath, randomJson)
    val first = StrictThreadStateV1(0, "Runnable", "example.Read", false, null)
    val second = StrictThreadStateV1(1, "Runnable", "example.Write", true, 42)
    val step = StrictScheduleStepV1(0, listOf(0, 1), listOf(first, second))
    val strict =
        StrictStateTraceV1(
            1,
            StrictFailureV1(
                failure.className,
                failure.message,
                failure.topClass,
                failure.topMethod,
                failure.topFile,
                failure.topLine,
            ),
            listOf(step),
        )
    // Legacy order is not guaranteed; the strict format sorts the same registered indices.
    val legacy = listOf(ScheduleRecording(0, listOf(1, 0), "example.Read"))
    fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun verify(legacyData: List<ScheduleRecording>, strictData: StrictStateTraceV1): Boolean {
      val legacyJson = Json.encodeToString(legacyData)
      val strictJson = Json.encodeToString(strictData)
      Files.writeString(legacyPath, legacyJson)
      Files.writeString(strictPath, strictJson)
      return CoroutineEpochBackend(temp)
          .verifyArtifacts(
              temp,
              manifest()
                  .copy(
                      scheduleDigest = digest(legacyJson),
                      randomDigest = digest(randomJson),
                      strictStateDigest = digest(strictJson),
                  ),
              System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
          )
    }
    assertTrue(verify(legacy, strict))
    assertFalse(verify(legacy, strict.copy(steps = listOf(step, step))))
    assertFalse(verify(legacy, strict.copy(steps = listOf(step.copy(selectedIndex = 1)))))
    assertFalse(
        verify(
            legacy,
            strict.copy(
                steps =
                    listOf(
                        step.copy(
                            threads = listOf(first.copy(pendingOperation = "example.Other"), second)
                        )
                    )
            ),
        )
    )
    val third = StrictThreadStateV1(2, "Runnable", "example.Other", false, null)
    assertFalse(
        verify(
            legacy,
            strict.copy(steps = listOf(step.copy(threads = listOf(first, second, third)))),
        )
    )
    assertFalse(verify(listOf(legacy.single().copy(enabled = listOf(0, 0, 1))), strict))
  }

  @Test
  fun placeholderWorkerRunsInFreshProcessAndRejectsWithoutLoadingTarget() {
    val input = temp.resolve("request.json")
    val output = temp.resolve("response.json")
    val marker = temp.resolve("target-was-loaded")
    val target =
        request.copy(
            entrypoint =
                Entrypoint("org.pastalab.fray.core.coroutines.TargetWithInitializer", "run"),
            cooperativeInputs = null,
        )
    Files.writeString(input, Json.encodeToString(target), StandardCharsets.UTF_8)
    val classes =
        listOf(
                CoroutineEpochWorker::class.java,
                EpochProtocolTest::class.java,
                Json::class.java,
                kotlinx.serialization.KSerializer::class.java,
                kotlin.Unit::class.java,
            )
            .mapNotNull {
              it.protectionDomain.codeSource?.location?.toURI()?.let(Path::of)?.toString()
            }
            .distinct()
            .joinToString(java.io.File.pathSeparator)
    val java = Path.of(System.getProperty("java.home"), "bin", "java")
    val process =
        ProcessBuilder(
                java.toString(),
                "-Dtest.marker=$marker",
                "-cp",
                classes,
                CoroutineEpochWorker::class.java.name,
                input.toString(),
                output.toString(),
            )
            .redirectErrorStream(true)
            .start()
    val done = process.waitFor(20, TimeUnit.SECONDS)
    if (!done) {
      process.destroyForcibly()
      process.waitFor()
    }
    assertTrue(done, "placeholder worker timed out")
    assertEquals(20, process.exitValue(), process.inputStream.bufferedReader().readText())
    val response = Json.decodeFromString<EpochResponse>(Files.readString(output))
    assertEquals(EpochStatus.UNSUPPORTED, response.status)
    assertEquals(target.nonce, response.nonce)
    assertFalse(Files.exists(marker))
  }
}

/** Would create a marker if the placeholder ever initialized the requested target. */
class TargetWithInitializer {
  companion object {
    init {
      System.getProperty("test.marker")?.let { Files.writeString(Path.of(it), "loaded") }
    }

    @JvmStatic fun run() {}
  }
}
