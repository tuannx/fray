package org.pastalab.fray.core.coroutines

import java.util.concurrent.CancellationException
import org.pastalab.fray.core.FrayInternalError
import org.pastalab.fray.runtime.TargetTerminateException

/** Exact app-site matching is intentionally narrow; no cause-chain attribution is attempted. */
internal fun classifyExpectedFailure(
    error: Throwable,
    expected: ExpectedApplicationFailure?,
    appClasses: Set<String>,
): FailureFingerprint? {
  expected ?: return null
  if (
      error is FrayInternalError ||
          error is TargetTerminateException ||
          error is CancellationException ||
          error is LinkageError ||
          error is VirtualMachineError ||
          error is ThreadDeath ||
          error.cause != null ||
          error.suppressed.isNotEmpty()
  )
      return null
  val frame = error.stackTrace.firstOrNull() ?: return null
  if (frame.className.replace('.', '/') !in appClasses || frame.lineNumber < 0) return null
  val value =
      FailureFingerprint(
          error.javaClass.name,
          error.message,
          frame.className,
          frame.methodName,
          frame.fileName,
          frame.lineNumber,
      )
  return value.takeIf(expected::matches)
}
