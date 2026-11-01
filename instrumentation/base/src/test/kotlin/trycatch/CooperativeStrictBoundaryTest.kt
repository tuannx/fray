package trycatch

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners

/** Direct state-machine checks; not a claim that an agent or loader has been integrated. */
class CooperativeStrictBoundaryTest {
  private val entry = "example/app/Entry"
  private val second = "example/app/Second"
  private val coroutine = "kotlinx/coroutines/Example"
  private val entryBytes = byteArrayOf(1, 2, 3)
  private val secondBytes = byteArrayOf(4, 5, 6)
  private val coroutineBytes = byteArrayOf(7, 8, 9)

  private fun hash(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  private fun owned(block: (ClassLoader) -> Unit) {
    val loader = object : ClassLoader(javaClass.classLoader) {}
    CoroutineInstrumentationOwners.register(loader)
    try {
      CoroutineInstrumentationOwners.registerAppGraph(
          loader,
          mapOf(entry to hash(entryBytes), second to hash(secondBytes)),
      )
      block(loader)
    } finally {
      CoroutineInstrumentationOwners.unregister(loader)
    }
  }

  private fun completeEntry(loader: ClassLoader) {
    CoroutineInstrumentationOwners.beginAppDefinition(loader, entry)
    CoroutineInstrumentationOwners.verifyAppInput(loader, entry, entryBytes)
    CoroutineInstrumentationOwners.markAppTransformed(loader, entry, entryBytes)
    CoroutineInstrumentationOwners.checkAppTransformed(loader, entry)
    CoroutineInstrumentationOwners.finishAppDefinition(loader, entry)
  }

  @Test
  fun rejectsMissingEntryAcknowledgement() = owned { loader ->
    assertFailsWith<IllegalStateException> {
      CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
    }
    assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(loader) }
  }

  @Test
  fun rejectsEachIncompleteAppDefinitionStage() {
    for (stage in 0..3) owned { loader ->
      completeEntry(loader)
      CoroutineInstrumentationOwners.beginAppDefinition(loader, second)
      if (stage >= 1) CoroutineInstrumentationOwners.verifyAppInput(loader, second, secondBytes)
      if (stage >= 2) CoroutineInstrumentationOwners.markAppTransformed(loader, second, secondBytes)
      if (stage >= 3) CoroutineInstrumentationOwners.checkAppTransformed(loader, second)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
      }
    }
  }

  @Test
  fun rejectsPendingCoroutineReservationVerificationAndAcknowledgement() {
    for (stage in 0..3) owned { loader ->
      completeEntry(loader)
      CoroutineInstrumentationOwners.expectCoroutineInput(loader, coroutine, coroutineBytes)
      if (stage >= 1)
          CoroutineInstrumentationOwners.verifyCoroutineInput(loader, coroutine, coroutineBytes)
      if (stage >= 2) CoroutineInstrumentationOwners.markTransformed(loader, coroutine)
      if (stage >= 3) CoroutineInstrumentationOwners.checkTransformed(loader, coroutine)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
      }
    }
  }

  @Test
  fun acceptsCompletedCallbacksWithoutRequiringUnusedClassesToLoad() = owned { loader ->
    completeEntry(loader)
    CoroutineInstrumentationOwners.expectCoroutineInput(loader, coroutine, coroutineBytes)
    CoroutineInstrumentationOwners.verifyCoroutineInput(loader, coroutine, coroutineBytes)
    CoroutineInstrumentationOwners.markTransformed(loader, coroutine)
    CoroutineInstrumentationOwners.checkTransformed(loader, coroutine)
    CoroutineInstrumentationOwners.finishCoroutineDefinition(loader, coroutine)
    CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
  }

  @Test
  fun pendingCachedResolutionPreventsSeal() {
    for (useCoroutine in listOf(false, true)) owned { loader ->
      completeEntry(loader)
      val name =
          if (useCoroutine) {
            CoroutineInstrumentationOwners.expectCoroutineInput(loader, coroutine, coroutineBytes)
            CoroutineInstrumentationOwners.verifyCoroutineInput(loader, coroutine, coroutineBytes)
            CoroutineInstrumentationOwners.markTransformed(loader, coroutine)
            CoroutineInstrumentationOwners.checkTransformed(loader, coroutine)
            CoroutineInstrumentationOwners.finishCoroutineDefinition(loader, coroutine)
            coroutine
          } else entry
      CoroutineInstrumentationOwners.beginCachedResolution(loader, name)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
      }
    }
  }

  @Test
  fun completedCachedResolutionCanSealAndLateResolutionPoisons() = owned { loader ->
    completeEntry(loader)
    CoroutineInstrumentationOwners.beginCachedResolution(loader, entry)
    CoroutineInstrumentationOwners.finishCachedResolution(loader, entry)
    CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
    assertFailsWith<IllegalStateException> {
      CoroutineInstrumentationOwners.beginCachedResolution(loader, entry)
    }
    assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(loader) }
  }

  @Test
  fun acknowledgedButUnfinishedAppPreventsConcurrentSeal() = owned { loader ->
    completeEntry(loader)
    val reserved = CountDownLatch(1)
    val release = CountDownLatch(1)
    val worker =
        thread(start = true, isDaemon = true) {
          CoroutineInstrumentationOwners.beginAppDefinition(loader, second)
          CoroutineInstrumentationOwners.verifyAppInput(loader, second, secondBytes)
          CoroutineInstrumentationOwners.markAppTransformed(loader, second, secondBytes)
          CoroutineInstrumentationOwners.checkAppTransformed(loader, second)
          // Reservation stays live while the loader's requested resolution is in progress.
          reserved.countDown()
          release.await(5, TimeUnit.SECONDS)
        }
    try {
      assertTrue(reserved.await(5, TimeUnit.SECONDS), "definition reservation did not start")
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
      }
    } finally {
      release.countDown()
      worker.join(TimeUnit.SECONDS.toMillis(5))
      assertTrue(!worker.isAlive, "definition test worker did not stop")
    }
  }

  @Test
  fun acknowledgedButUnfinishedCoroutinePreventsConcurrentSeal() = owned { loader ->
    completeEntry(loader)
    val reserved = CountDownLatch(1)
    val release = CountDownLatch(1)
    val worker =
        thread(start = true, isDaemon = true) {
          CoroutineInstrumentationOwners.expectCoroutineInput(loader, coroutine, coroutineBytes)
          CoroutineInstrumentationOwners.verifyCoroutineInput(loader, coroutine, coroutineBytes)
          CoroutineInstrumentationOwners.markTransformed(loader, coroutine)
          CoroutineInstrumentationOwners.checkTransformed(loader, coroutine)
          reserved.countDown()
          release.await(5, TimeUnit.SECONDS)
        }
    try {
      assertTrue(reserved.await(5, TimeUnit.SECONDS), "coroutine reservation did not start")
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkStrictBoundary(loader, entry)
      }
    } finally {
      release.countDown()
      worker.join(TimeUnit.SECONDS.toMillis(5))
      assertTrue(!worker.isAlive, "coroutine test worker did not stop")
    }
  }
}
