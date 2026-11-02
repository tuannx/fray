package org.pastalab.fray.core.coroutines

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.pastalab.fray.core.command.MethodExecutor

/** Explicitly opted-in local child probes. A timeout is not proof the target reached suspension. */
@EnabledIfEnvironmentVariable(named = "FRAY_COOPERATIVE_INTEGRATION", matches = "true")
class CooperativeNegativeWorkerTest {
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
  fun detachedIsRejectedAndSuspendedCannotSilentlyPass() {
    assumeTrue(System.getenv("FRAY_COOPERATIVE_NEGATIVES") == "true")
    val fixtureDir = Path.of(property("fixture.dir")).resolve("org/pastalab/fray/test/poc")
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
    val agent = pin(Path.of(property("agent")))
    val jvmti = pin(Path.of(property("jvmti")))
    val backend = CoroutineEpochBackend(temp.resolve("negative-epochs"))

    fun probe(name: String, timeoutMillis: Long): EpochOutcome {
      val fixture =
          Files.list(fixtureDir).use { stream ->
            stream
                .filter { path ->
                  val filename = path.fileName.toString()
                  filename == "$name.class" ||
                      (filename.startsWith("$name\$") && filename.endsWith(".class"))
                }
                .sorted()
                .iterator()
                .asSequence()
                .toList()
          }
      require(fixture.size >= 2) { "Missing compiled $name classes" }
      val app = temp.resolve("$name.jar")
      val classes = linkedMapOf<String, String>()
      ZipOutputStream(Files.newOutputStream(app)).use { zip ->
        for (file in fixture) {
          val className =
              "org/pastalab/fray/test/poc/" + file.fileName.toString().removeSuffix(".class")
          val bytes = Files.readAllBytes(file)
          classes[className] = digest(bytes)
          zip.putNextEntry(ZipEntry("$className.class"))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
      val entry = Entrypoint("org.pastalab.fray.test.poc.$name", "run")
      val archive = CooperativeApplicationArchive(app, digest(Files.readAllBytes(app)), classes)
      if (name == "CooperativeSuspendedCase") {
        val admitted = CooperativeApplicationArchiveReader.read(archive, entry)
        assertEquals(
            classes.keys,
            admitted.classNames(),
            "structured fixture must reach worker execution",
        )
        println("NEGATIVE_SUSPENDED_GRAPH_ADMITTED")
      } else if (name == "CooperativeDetachedCase") {
        val rejection =
            assertFailsWith<IllegalArgumentException> {
              CooperativeApplicationArchiveReader.read(archive, entry)
            }
        assertTrue(
            rejection.message.orEmpty().contains("unsupported field reference"),
            "GlobalScope field should cause rejection: ${rejection.message}",
        )
        println("NEGATIVE_DETACHED_GLOBAL_SCOPE_REJECTED")
      }
      val jars = libraries + listOf(app.toRealPath())
      val runtime = EpochRuntimeSpec(pin(java), pin(modules), agent, jvmti, jars.map(::pin))
      val inputs =
          CooperativeExecutionInputs(
              app.toString(),
              archive.sha256,
              classes,
              stdlib.toString(),
              coroutines.toString(),
          )
      val method =
          MethodExecutor(
              entry.className,
              entry.methodName,
              emptyList(),
              jars.map(Path::toString),
              emptyMap(),
          )
      return backend.run(method, true, 74231, runtime, timeoutMillis, cooperativeInputs = inputs)
    }

    val detached = probe("CooperativeDetachedCase", 60_000)
    assertEquals(EpochStatus.UNSUPPORTED, detached.status, "detached: ${detached.reason}")
    assertEquals("Cooperative app or runtime policy was rejected", detached.reason)
    println("NEGATIVE_DETACHED_WORKER_UNSUPPORTED")

    // Budget includes hashing the complete JDK image before and after the child, not just workload
    // time.
    val suspended = probe("CooperativeSuspendedCase", 15_000)
    assertEquals(EpochStatus.INCONCLUSIVE, suspended.status, "suspended: ${suspended.reason}")
    val suspendedLog = Files.readString(assertNotNull(suspended.diagnosticLog))
    assertTrue(
        suspended.reason == "Child exceeded epoch deadline" ||
            suspended.reason == "Workload, lifecycle or Fray completion was not verified" ||
            suspended.reason == "Epoch deadline exceeded during post-run verification",
        "unexpected suspended result: ${suspended.reason}\n$suspendedLog",
    )
    assertTrue(
        suspendedLog.contains("COOPERATIVE_PARENT_APP_GRAPH_INVISIBLE"),
        "worker did not admit graph",
    )
    assertTrue(suspendedLog.contains("Fray Testing:"), "worker did not enter Fray runner")
    println(
        "NEGATIVE_SUSPENDED_RUNNER_STARTED INCONCLUSIVE reason=${suspended.reason} suspension-site-not-observed"
    )

    val cancelled = probe("CooperativeCancellationCase", 60_000)
    if (cancelled.status == EpochStatus.UNSUPPORTED) {
      println("STRUCTURED_CANCELLATION_UNSUPPORTED ${cancelled.reason}")
    } else {
      val log =
          cancelled.diagnosticLog?.takeIf { Files.exists(it) }?.let(Files::readString)
              ?: "No bounded worker log"
      assertEquals(
          EpochStatus.COMPLETED,
          cancelled.status,
          "cancellation: ${cancelled.reason}\n$log",
      )
      val evidence = assertNotNull(cancelled.response).evidence
      assertTrue(
          evidence.admissionVerified &&
              evidence.workloadTerminated &&
              evidence.jobBoundaryVerified &&
              evidence.callbacksDrained &&
              evidence.teardownComplete &&
              evidence.noUncaughtFailure
      )
      assertTrue(log.contains("COOPERATIVE_PARENT_APP_GRAPH_INVISIBLE"))
      println("STRUCTURED_CANCELLATION_COMPLETED workload=1 boundary=1 teardown=1 helper=stopped")
    }
  }
}
