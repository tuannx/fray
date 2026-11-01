package org.pastalab.fray.core.randomness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StrictRecordedIntRandomnessTest {
  private fun strict(vararg values: Int): StrictRecordedIntRandomness =
      StrictRecordedIntRandomness.fromRecorded(ControlledRandom(values.toMutableList()))

  @Test
  fun consumesOnlyCopiedIntegersAndCapturesCompleteBoundary() {
    val recorded = ControlledRandom(mutableListOf(3, 7))
    val strict = StrictRecordedIntRandomness.fromRecorded(recorded)
    recorded.integers[0] = 99
    assertEquals(3, strict.nextInt())
    assertEquals(7, strict.nextInt())
    assertNull(strict.captureAtFirstError())
    assertNull(strict.violation())
    strict.done()
    assertFailsWith<IllegalStateException> { strict.nextInt() }
    assertNotNull(strict.violation())
  }

  @Test
  fun rejectsAndLatchesOverrunEvenIfCallerCatchesIt() {
    val strict = strict(1)
    assertEquals(1, strict.nextInt())
    assertFailsWith<IllegalStateException> { strict.nextInt() }
    assertNotNull(strict.captureAtFirstError())
    assertNotNull(strict.violation())
  }

  @Test
  fun rejectsUnderrunAtFirstErrorBeforeTeardownCanConsumeIt() {
    val strict = strict(1, 2)
    assertEquals(1, strict.nextInt())
    assertNotNull(strict.captureAtFirstError())
    assertFailsWith<IllegalStateException> { strict.nextInt() }
    assertNotNull(strict.violation())
  }

  @Test
  fun rejectsRecordedAndRequestedDoubles() {
    assertFailsWith<IllegalArgumentException> {
      StrictRecordedIntRandomness.fromRecorded(
          ControlledRandom(mutableListOf(), mutableListOf(0.5))
      )
    }
    val unbounded = strict()
    assertFailsWith<IllegalStateException> { unbounded.nextDouble() }
    assertNotNull(unbounded.captureAtFirstError())

    val bounded = strict()
    assertFailsWith<IllegalStateException> { bounded.nextDouble(1.0, 2.0) }
    assertNotNull(bounded.captureAtFirstError())
  }

  @Test
  fun rejectsInvalidValuesAndReuse() {
    assertFailsWith<IllegalArgumentException> { strict(-1) }
    assertFailsWith<IllegalArgumentException> { strict(Int.MAX_VALUE) }
    assertFailsWith<IllegalArgumentException> { StrictRecordedIntRandomness.fromRecorded(strict()) }

    val earlyDone = strict()
    assertFailsWith<IllegalStateException> { earlyDone.done() }
    assertNotNull(earlyDone.captureAtFirstError())

    val reused = strict()
    assertNull(reused.captureAtFirstError())
    assertNotNull(reused.captureAtFirstError())
    assertFailsWith<IllegalStateException> {
      reused.done()
      reused.done()
    }
  }
}
