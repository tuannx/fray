package org.pastalab.fray.core.coroutines

import java.time.Duration
import java.util.concurrent.TimeUnit

/** Result for the Process created by Fray. It says nothing about other processes. */
internal data class ProcessCleanupResult(
    val rootExited: Boolean,
    val operationFailed: Boolean,
    val interrupted: Boolean,
)

/**
 * Request termination of the original child and confirm its exit within one wait budget. Supported
 * workloads reject host-process creation. This does not inspect or verify descendants, and is not
 * an isolation boundary against hostile code.
 *
 * Process.destroyForcibly is a request: only a successful wait and a non-alive process confirm
 * termination. A zero timeout still polls once. Interrupts are recorded and restored after the
 * attempt, rather than preventing cleanup. The budget bounds waits, not operating-system calls.
 */
internal fun cleanupProcess(child: Process, timeout: Duration): ProcessCleanupResult {
  require(!timeout.isNegative && timeout <= Duration.ofMinutes(1))
  val start = System.nanoTime()
  val budget = timeout.toNanos()
  fun remaining(): Long = (budget - (System.nanoTime() - start)).coerceAtLeast(0)

  var interrupted = Thread.interrupted()
  var operationFailed = false
  var rootExited = false
  try {
    try {
      if (child.isAlive) child.destroyForcibly()
    } catch (_: Exception) {
      operationFailed = true
    }
    while (true) {
      try {
        val terminated = child.waitFor(remaining(), TimeUnit.NANOSECONDS)
        rootExited = terminated && !child.isAlive
        break
      } catch (_: InterruptedException) {
        interrupted = true
        if (remaining() == 0L) break
      } catch (_: Exception) {
        operationFailed = true
        break
      }
    }
    return ProcessCleanupResult(rootExited, operationFailed, interrupted)
  } finally {
    if (interrupted) Thread.currentThread().interrupt()
  }
}
