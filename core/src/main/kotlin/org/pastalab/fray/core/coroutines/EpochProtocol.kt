package org.pastalab.fray.core.coroutines

import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Lab-only protocol. The worker is trusted framework code, not a JVM security sandbox. */
@Serializable
enum class EpochStatus {
  COMPLETED,
  APPLICATION_BUG,
  UNSUPPORTED,
  INCONCLUSIVE,
}

@Serializable data class Entrypoint(val className: String, val methodName: String)

@Serializable
data class FailureFingerprint(
    val className: String,
    val message: String?,
    val topClass: String,
    val topMethod: String,
    val topFile: String?,
    val topLine: Int,
)

/** Explicit expected site for the restricted lab recorder; it is not a generic bug classifier. */
@Serializable
data class ExpectedApplicationFailure(
    val className: String,
    val topClass: String,
    val topMethod: String,
    val messagePrefix: String,
) {
  internal fun isValid(): Boolean =
      listOf(className, topClass, topMethod, messagePrefix).all {
        it.isNotBlank() && it.length <= 512
      }

  internal fun matches(failure: FailureFingerprint): Boolean =
      isValid() &&
          failure.className == className &&
          failure.topClass == topClass &&
          failure.topMethod == topMethod &&
          failure.message?.startsWith(messagePrefix) == true
}

/** Hashes bind the supplied artifacts; they do not assert that replay is deterministic. */
@Serializable
data class ReplayManifest(
    val schema: Int = 4,
    val entrypoint: Entrypoint,
    val seed: Long,
    val classpathDigest: String,
    val childClasspathDigest: String,
    val appJarSha256: String,
    val appClasses: Map<String, String>,
    val runtimeDigest: String,
    val processPolicyDigest: String,
    val scheduleDigest: String,
    val randomDigest: String,
    val strictStateVersion: Int = 1,
    val strictStateDigest: String,
    val expectedFailure: ExpectedApplicationFailure,
    val failure: FailureFingerprint,
    val coroutineVersion: String = "1.10.1",
    val freshProcessOnly: Boolean = true,
)

/**
 * Explicit local sources for the opt-in cooperative child. Every path must be in the pinned
 * classpath.
 */
@Serializable
data class CooperativeExecutionInputs(
    val appJar: String,
    val appJarSha256: String,
    val appClasses: Map<String, String>,
    val stdlibJar: String,
    val coroutineJar: String,
)

@Serializable
data class EpochRequest(
    val schema: Int = 1,
    val nonce: String,
    val entrypoint: Entrypoint,
    val seed: Long,
    val classpathDigest: String,
    val childClasspathDigest: String,
    val runtimeDigest: String,
    val processPolicyDigest: String,
    val expectedFailure: ExpectedApplicationFailure? = null,
    val cooperativeInputs: CooperativeExecutionInputs? = null,
    val replay: Boolean = false,
    val replayManifest: ReplayManifest? = null,
)

/** These flags are set by the trusted worker only, never inferred from a null exception. */
@Serializable
data class EpochEvidence(
    val admissionVerified: Boolean = false,
    /** The target returned or its exact application failure was captured, not merely null. */
    val workloadTerminated: Boolean = false,
    val jobBoundaryVerified: Boolean = false,
    val callbacksDrained: Boolean = false,
    val teardownComplete: Boolean = false,
    val noUncaughtFailure: Boolean = false,
    val strictReplayVerified: Boolean = false,
)

@Serializable
data class EpochResponse(
    val schema: Int = 1,
    val nonce: String,
    val status: EpochStatus,
    val reason: String? = null,
    val evidence: EpochEvidence = EpochEvidence(),
    val failure: FailureFingerprint? = null,
    val manifest: ReplayManifest? = null,
)

data class EpochOutcome(
    val status: EpochStatus,
    val reason: String,
    val response: EpochResponse? = null,
    val artifactDirectory: Path? = null,
    val diagnosticLog: Path? = null,
)

/** Mismatched/incomplete protocol data is always inconclusive, never a successful test. */
internal object EpochProtocolValidator {
  private val digest = Regex("[0-9a-f]{64}")
  private val className = Regex("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*")

  internal fun validAppInventory(sha256: String, classes: Map<String, String>): Boolean =
      digest.matches(sha256) &&
          classes.isNotEmpty() &&
          classes.size <= 1000 &&
          classes.all { (name, hash) ->
            name.length <= 512 && className.matches(name) && digest.matches(hash)
          }

  fun validate(request: EpochRequest, response: EpochResponse, exitCode: Int): EpochOutcome {
    fun inconclusive(reason: String) = EpochOutcome(EpochStatus.INCONCLUSIVE, reason)
    if (
        request.schema != 1 ||
            response.schema != 1 ||
            request.nonce.isBlank() ||
            request.nonce.length > 128 ||
            request.nonce != response.nonce ||
            !digest.matches(request.classpathDigest) ||
            !digest.matches(request.childClasspathDigest) ||
            !digest.matches(request.runtimeDigest) ||
            !digest.matches(request.processPolicyDigest) ||
            request.expectedFailure?.isValid() == false ||
            request.replay != (request.replayManifest != null)
    ) {
      return inconclusive("Invalid or mismatched epoch protocol")
    }
    val expectedExit =
        when (response.status) {
          EpochStatus.COMPLETED -> 0
          EpochStatus.APPLICATION_BUG -> 10
          EpochStatus.UNSUPPORTED -> 20
          EpochStatus.INCONCLUSIVE -> 30
        }
    if (exitCode != expectedExit) return inconclusive("Child exit does not match response")
    if (response.status == EpochStatus.UNSUPPORTED || response.status == EpochStatus.INCONCLUSIVE) {
      if (response.reason.isNullOrBlank()) return inconclusive("Child gave no reason")
      return EpochOutcome(response.status, response.reason, response)
    }
    val e = response.evidence
    if (
        !(e.admissionVerified &&
            e.workloadTerminated &&
            e.jobBoundaryVerified &&
            e.callbacksDrained &&
            e.teardownComplete &&
            e.noUncaughtFailure) || (request.replay && !e.strictReplayVerified)
    ) {
      return inconclusive("Completion, ownership, teardown or replay evidence is missing")
    }
    if (response.status == EpochStatus.COMPLETED) {
      if (response.failure != null || response.manifest != null || request.replay) {
        return inconclusive("Unexpected successful replay or failure artifact")
      }
      return EpochOutcome(EpochStatus.COMPLETED, "Completed", response)
    }
    val failure = response.failure ?: return inconclusive("Application error fingerprint missing")
    val manifest = response.manifest ?: return inconclusive("Replay manifest missing")
    val inputs =
        request.cooperativeInputs ?: return inconclusive("Cooperative app identity missing")
    val expected =
        request.expectedFailure
            ?: return inconclusive("Expected application failure is not configured")
    if (
        failure.className.isBlank() ||
            failure.topClass.isBlank() ||
            failure.topMethod.isBlank() ||
            failure.topLine < 0 ||
            failure.className.startsWith("org.pastalab.fray.runtime.") ||
            failure.className == "org.pastalab.fray.core.FrayInternalError" ||
            failure.topClass.startsWith("org.pastalab.fray.core.") ||
            failure.topClass.startsWith("org.pastalab.fray.runtime.") ||
            !expected.matches(failure) ||
            manifest.schema != 4 ||
            !validAppInventory(manifest.appJarSha256, manifest.appClasses) ||
            manifest.appJarSha256 != inputs.appJarSha256 ||
            manifest.appClasses != inputs.appClasses ||
            failure.topClass.replace('.', '/') !in manifest.appClasses ||
            manifest.childClasspathDigest != request.childClasspathDigest ||
            manifest.expectedFailure != expected ||
            manifest.strictStateVersion != 1 ||
            manifest.failure != failure ||
            manifest.entrypoint != request.entrypoint ||
            manifest.classpathDigest != request.classpathDigest ||
            manifest.runtimeDigest != request.runtimeDigest ||
            manifest.seed != request.seed ||
            manifest.processPolicyDigest != request.processPolicyDigest ||
            !digest.matches(manifest.scheduleDigest) ||
            !digest.matches(manifest.randomDigest) ||
            !digest.matches(manifest.strictStateDigest) ||
            manifest.coroutineVersion != "1.10.1" ||
            !manifest.freshProcessOnly
    ) {
      return inconclusive("Invalid replay manifest")
    }
    if (request.replay && manifest != request.replayManifest)
        return inconclusive("Replay artifact changed")
    return EpochOutcome(
        EpochStatus.APPLICATION_BUG,
        "Application error with recorded artifact",
        response,
    )
  }
}
