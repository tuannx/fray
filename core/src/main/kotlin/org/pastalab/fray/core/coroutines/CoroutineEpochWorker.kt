package org.pastalab.fray.core.coroutines

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.pastalab.fray.core.command.FrozenTrustedRuntimeResolver

/** Opt-in disposable child. Missing cooperative inputs remain unsupported. */
object CoroutineEpochWorker {
  @JvmStatic
  fun main(args: Array<String>) {
    if (args.size != 2) exitProcess(30)
    try {
      val input = Path.of(args[0]).toAbsolutePath().normalize()
      val output = Path.of(args[1]).toAbsolutePath().normalize()
      require(
          input.parent == output.parent &&
              input.fileName.toString() == "request.json" &&
              output.fileName.toString() == "response.json"
      )
      require(
          !Files.isSymbolicLink(input) &&
              Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) &&
              !Files.exists(output, LinkOption.NOFOLLOW_LINKS)
      )
      val bytes =
          Files.newInputStream(input).use {
            it.readNBytes(CoroutineEpochBackend.MAX_PROTOCOL_BYTES + 1)
          }
      require(bytes.size <= CoroutineEpochBackend.MAX_PROTOCOL_BYTES)
      val request = Json.decodeFromString<EpochRequest>(bytes.toString(StandardCharsets.UTF_8))
      require(request.schema == 1 && request.nonce.isNotBlank())
      val response = runRequest(request, input.parent)
      val temporary = Files.createTempFile(output.parent, "response-", ".tmp")
      Files.writeString(temporary, Json.encodeToString(response), StandardCharsets.UTF_8)
      Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE)
      exitProcess(
          when (response.status) {
            EpochStatus.COMPLETED -> 0
            EpochStatus.APPLICATION_BUG -> 10
            EpochStatus.UNSUPPORTED -> 20
            EpochStatus.INCONCLUSIVE -> 30
          }
      )
    } catch (_: Exception) {
      // No partial or fabricated success response on malformed input or filesystem failure.
      exitProcess(30)
    }
  }

  private fun runRequest(request: EpochRequest, epoch: Path): EpochResponse {
    fun unsupported(reason: String) =
        EpochResponse(nonce = request.nonce, status = EpochStatus.UNSUPPORTED, reason = reason)
    val inputs =
        request.cooperativeInputs ?: return unsupported("No cooperative app/runtime manifest")
    return try {
      val classpath =
          System.getProperty("java.class.path").split(File.pathSeparator).map {
            Path.of(it).toRealPath()
          }
      require(classpath.isNotEmpty() && classpath.distinct().size == classpath.size)
      val app = Path.of(inputs.appJar).toRealPath()
      val stdlib = Path.of(inputs.stdlibJar).toRealPath()
      val coroutine = Path.of(inputs.coroutineJar).toRealPath()
      require(
          setOf(app, stdlib, coroutine).size == 3 &&
              app !in classpath &&
              listOf(stdlib, coroutine).all { it in classpath }
      )
      val graph =
          CooperativeApplicationArchiveReader.read(
              CooperativeApplicationArchive(app, inputs.appJarSha256, inputs.appClasses),
              request.entrypoint,
          )
      val parent = Thread.currentThread().contextClassLoader
      // Reject any parent archive shadow, including continuation/helper classes, before trying
      // parent loading. A non-initializing Class.forName also catches bootstrap-visible names.
      val parentHasAppClass =
          classpath.any { jar ->
            JarFile(jar.toFile()).use { file ->
              graph.classNames().any { file.getJarEntry("$it.class") != null }
            }
          }
      if (parentHasAppClass) {
        System.out.println("COOPERATIVE_PARENT_APP_SHADOW_REJECTED")
        return unsupported("App graph is shadowed in the parent classpath")
      }
      for (internalName in graph.classNames()) {
        val found =
            try {
              Class.forName(internalName.replace('/', '.'), false, parent)
              true
            } catch (_: ClassNotFoundException) {
              false
            }
        if (found) {
          System.out.println("COOPERATIVE_PARENT_APP_SHADOW_REJECTED")
          return unsupported("App graph is visible from the parent loader")
        }
      }
      System.out.println("COOPERATIVE_PARENT_APP_GRAPH_INVISIBLE")
      val resolver = FrozenTrustedRuntimeResolver.create(parent, stdlib, coroutine, classpath)
      val response =
          CooperativeEpochExecution.run(
              request,
              graph,
              classpath.map { it.toUri().toURL() }.toTypedArray(),
              resolver,
              epoch.resolve("run-report"),
          )
      resolver.verifySourcesUnchanged()
      response
    } catch (error: IllegalArgumentException) {
      CooperativeDiagnostics.failure(error)
      unsupported("Cooperative app or runtime policy was rejected")
    } catch (error: ClassNotFoundException) {
      CooperativeDiagnostics.failure(error)
      unsupported("A required runtime class is unsupported")
    } catch (error: Throwable) {
      CooperativeDiagnostics.failure(error)
      EpochResponse(
          nonce = request.nonce,
          status = EpochStatus.INCONCLUSIVE,
          reason = "Cooperative child could not establish a complete outcome",
      )
    }
  }
}
