package org.pastalab.fray.core.coroutines

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutputReaderGuardTest {
  @Test
  fun `successful action leaves failure flag clear`() {
    val failed = AtomicBoolean(false)
    var ran = false
    guardOutputReader(failed) { ran = true }
    assertTrue(ran)
    assertFalse(failed.get())
  }

  @Test
  fun `ordinary exception marks failure and is swallowed`() {
    val failed = AtomicBoolean(false)
    guardOutputReader(failed) { throw IOException("capture failed") }
    assertTrue(failed.get())
  }

  @Test
  fun `error marks failure and is rethrown`() {
    val failed = AtomicBoolean(false)
    val error = AssertionError("reader error")
    val thrown = assertFailsWith<AssertionError> { guardOutputReader(failed) { throw error } }
    assertSame(error, thrown)
    assertTrue(failed.get())
  }
}
