package org.pastalab.fray.core.randomness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Experimental, single-use playback for a recording containing only ControlledRandom INT draws.
 * This verifies integer consumption, not call-site identity, scheduler draw alignment, or any RNG
 * outside Fray. It must not be used as evidence of fully deterministic coroutine replay.
 */
@Serializable
@SerialName("strict-recorded-int")
class StrictRecordedIntRandomness
private constructor(
    private val recordedIntegers: List<Int>,
    private val recordedDoubles: List<Double>,
) : Randomness {
  init {
    require(recordedDoubles.isEmpty()) { "Strict INT replay does not support recorded doubles" }
    require(recordedIntegers.all { it >= 0 && it < Int.MAX_VALUE }) {
      "Strict INT replay contains a value outside ControlledRandom's range"
    }
  }

  @Transient private val values = recordedIntegers.toList()
  @Transient private var index = 0
  @Transient private var firstErrorCaptured = false
  @Transient private var closed = false
  @Transient private var failure: String? = null

  companion object {
    /** Copy data from a freshly decoded legacy recording; never extend the original stream. */
    @JvmStatic
    fun fromRecorded(randomness: Randomness): StrictRecordedIntRandomness {
      require(randomness is ControlledRandom) { "Unsupported recorded randomness type" }
      return StrictRecordedIntRandomness(randomness.integers.toList(), randomness.doubles.toList())
    }
  }

  @Synchronized
  override fun nextInt(): Int {
    if (closed) reject("Strict INT replay was already closed")
    if (firstErrorCaptured) reject("Unexpected INT draw after the first-error boundary")
    if (index >= values.size) reject("Strict INT replay exceeded the recording at index $index")
    return values[index++]
  }

  @Synchronized
  override fun nextDouble(): Double = reject("Strict INT replay does not support DOUBLE draws")

  @Synchronized
  override fun nextDouble(origin: Double, bound: Double): Double =
      reject("Strict INT replay does not support bounded DOUBLE draws")

  /** Freeze consumption exactly at the first reported error, before teardown can consume draws. */
  @Synchronized
  fun captureAtFirstError(): String? {
    if (firstErrorCaptured) {
      latch("Strict INT replay captured more than one first-error boundary")
      return failure
    }
    firstErrorCaptured = true
    if (closed) latch("Strict INT replay closed before the first-error boundary")
    if (index != values.size) {
      latch("Strict INT replay consumed $index of ${values.size} recorded draws at the first error")
    }
    return failure
  }

  /** Later unsupported accesses remain observable even if Fray catches their exceptions. */
  @Synchronized fun violation(): String? = failure

  @Synchronized
  override fun done() {
    if (closed) reject("Strict INT replay was already closed")
    if (!firstErrorCaptured) reject("Strict INT replay closed before the first-error boundary")
    closed = true
  }

  private fun latch(message: String) {
    if (failure == null) failure = message
  }

  private fun reject(message: String): Nothing {
    latch(message)
    throw IllegalStateException(message)
  }
}
