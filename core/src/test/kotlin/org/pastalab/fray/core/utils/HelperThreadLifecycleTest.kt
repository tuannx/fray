package org.pastalab.fray.core.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HelperThreadLifecycleTest {
  @Test
  fun stoppedHelperFinishesItsCurrentJobAndTerminates() {
    val helper = HelperThread()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completed = CountDownLatch(1)
    helper.start()
    try {
      helper.submit {
        entered.countDown()
        if (release.await(5, TimeUnit.SECONDS)) completed.countDown()
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "helper job did not start")
      helper.stopHelperThread()
      helper.join(100)
      assertTrue(helper.isAlive, "helper terminated before the active job returned")
      release.countDown()
      assertTrue(completed.await(5, TimeUnit.SECONDS), "active job did not finish")
      helper.join(5_000)
      assertFalse(helper.isAlive, "helper did not terminate after its job returned")
      assertNull(helper.firstFailure)
    } finally {
      release.countDown()
      helper.stopHelperThread()
      helper.join(5_000)
    }
  }

  @Test
  fun firstCaughtThrowableRemainsVisibleAfterLaterFailures() {
    val helper = HelperThread()
    val firstStarted = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val afterFailures = CountDownLatch(1)
    val first = AssertionError("first helper error")
    val second = IllegalStateException("second helper error")
    helper.enableFailureRetention()
    helper.start()
    try {
      helper.submit {
        firstStarted.countDown()
        throw first
      }
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "first helper job did not start")
      helper.submit {
        secondStarted.countDown()
        throw second
      }
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "second helper job did not start")
      helper.submit { afterFailures.countDown() }
      assertTrue(afterFailures.await(5, TimeUnit.SECONDS), "helper did not continue after errors")
      helper.stopHelperThread()
      helper.join(5_000)
      assertFalse(helper.isAlive, "helper did not terminate")
      assertSame(
          first,
          helper.firstFailure,
          "later errors must not overwrite the first caught Throwable",
      )
    } finally {
      helper.stopHelperThread()
      helper.join(5_000)
    }
  }

  @Test
  fun defaultModeDoesNotRetainCaughtThrowable() {
    val helper = HelperThread()
    val began = CountDownLatch(1)
    val continued = CountDownLatch(1)
    helper.start()
    try {
      helper.submit {
        began.countDown()
        throw IllegalStateException("uncaptured helper error")
      }
      assertTrue(began.await(5, TimeUnit.SECONDS), "failing job did not start")
      helper.submit { continued.countDown() }
      assertTrue(continued.await(5, TimeUnit.SECONDS), "helper did not continue")
      helper.stopHelperThread()
      assertTrue(helper.awaitTermination(5_000), "helper did not terminate")
      assertNull(helper.firstFailure)
    } finally {
      helper.stopHelperThread()
      helper.join(5_000)
    }
  }
}
