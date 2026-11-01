package org.pastalab.fray.core.coroutines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CooperativeUnsupportedDispositionTest {
  @Test
  fun rejectionCannotHideHelperUncaughtOrDifferentRunnerFailure() {
    val rejection = IllegalStateException("unsupported coroutine ownership")
    fun disposition(
        result: Throwable? = rejection,
        stopped: Boolean = true,
        helperFailure: Throwable? = null,
        uncaught: Throwable? = null,
    ) = unsupportedDisposition("unsupported", rejection, result, stopped, helperFailure, uncaught)

    assertEquals(EpochStatus.UNSUPPORTED, disposition())
    assertEquals(EpochStatus.INCONCLUSIVE, disposition(stopped = false))
    assertEquals(EpochStatus.INCONCLUSIVE, disposition(helperFailure = IllegalStateException()))
    assertEquals(EpochStatus.INCONCLUSIVE, disposition(uncaught = IllegalStateException()))
    assertEquals(EpochStatus.INCONCLUSIVE, disposition(result = IllegalStateException()))
    assertEquals(EpochStatus.INCONCLUSIVE, disposition(result = null))
    assertEquals(
        EpochStatus.INCONCLUSIVE,
        unsupportedDisposition("unsupported", null, rejection, true, null, null),
    )
    assertNull(unsupportedDisposition(null, null, null, true, null, null))
  }
}
