package org.pastalab.fray.core.coroutines

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class ProcessCleanupTest {
  @Test
  fun `terminates and reaps a real child`() {
    assumeTrue(Files.isExecutable(Path.of("/bin/sleep")))
    val child = ProcessBuilder("/bin/sleep", "60").start()
    try {
      val result = cleanupProcess(child, Duration.ofSeconds(5))
      assertTrue(result.rootExited, "cleanup result: $result")
      assertFalse(result.operationFailed)
      assertFalse(child.isAlive)
      assertTrue(child.waitFor(0, TimeUnit.NANOSECONDS))
    } finally {
      child.destroyForcibly()
      child.waitFor(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `failed termination is reported without assuming destroy succeeded`() {
    val child = FakeProcess()
    val result = cleanupProcess(child, Duration.ofMillis(20))
    assertTrue(child.destroyRequested)
    assertFalse(result.rootExited)
    assertFalse(result.operationFailed)
    assertEquals(1, child.waitCalls)
  }

  @Test
  fun `interrupt during wait is recorded and restored after cleanup`() {
    val child = FakeProcess(interruptOnce = true, terminateAfterInterrupt = true)
    try {
      val result = cleanupProcess(child, Duration.ofSeconds(1))
      assertTrue(result.rootExited)
      assertTrue(result.interrupted)
      assertTrue(Thread.currentThread().isInterrupted)
      assertEquals(2, child.waitCalls)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `existing interrupt does not skip cleanup`() {
    val child = FakeProcess()
    try {
      Thread.currentThread().interrupt()
      val result = cleanupProcess(child, Duration.ZERO)
      assertTrue(child.destroyRequested)
      assertEquals(1, child.waitCalls)
      assertFalse(result.rootExited)
      assertTrue(result.interrupted)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `destroy failure is reported even if process later exits`() {
    val child = FakeProcess(destroyFails = true, alreadyExitedOnWait = true)
    val result = cleanupProcess(child, Duration.ofMillis(20))
    assertTrue(result.operationFailed)
    assertTrue(result.rootExited)
  }

  private class FakeProcess(
      private val interruptOnce: Boolean = false,
      private val terminateAfterInterrupt: Boolean = false,
      private val destroyFails: Boolean = false,
      private val alreadyExitedOnWait: Boolean = false,
  ) : Process() {
    var destroyRequested = false
    var waitCalls = 0
    private var alive = true

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())

    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

    override fun isAlive(): Boolean = alive

    override fun destroy() {
      destroyRequested = true
    }

    override fun destroyForcibly(): Process {
      destroyRequested = true
      if (destroyFails) throw SecurityException("denied")
      return this
    }

    override fun waitFor(): Int = throw UnsupportedOperationException()

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
      waitCalls++
      if (interruptOnce && waitCalls == 1) throw InterruptedException()
      if (alreadyExitedOnWait || (terminateAfterInterrupt && waitCalls > 1)) alive = false
      return !alive
    }

    override fun exitValue(): Int {
      if (alive) throw IllegalThreadStateException()
      return 0
    }
  }
}
