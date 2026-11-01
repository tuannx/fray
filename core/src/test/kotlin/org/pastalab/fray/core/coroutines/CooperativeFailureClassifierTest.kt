package org.pastalab.fray.core.coroutines

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CooperativeFailureClassifierTest {
  private val frame = StackTraceElement("example.Workload", "run", "Workload.kt", 12)
  private val classes = setOf("example/Workload")

  private fun expected(error: Throwable) =
      ExpectedApplicationFailure(
          error.javaClass.name,
          frame.className,
          frame.methodName,
          "expected",
      )

  @Test
  fun onlyExactUnwrappedApplicationSiteIsAccepted() {
    val app = IllegalStateException("expected failure").apply { stackTrace = arrayOf(frame) }
    assertNotNull(classifyExpectedFailure(app, expected(app), classes))
    assertNull(classifyExpectedFailure(app, expected(app).copy(topMethod = "other"), classes))
    assertNull(classifyExpectedFailure(app, expected(app), emptySet()))
    val wrapped =
        IllegalStateException("expected failure", IllegalStateException("cause")).apply {
          stackTrace = arrayOf(frame)
        }
    assertNull(classifyExpectedFailure(wrapped, expected(wrapped), classes))
    app.addSuppressed(IllegalStateException("additional"))
    assertNull(classifyExpectedFailure(app, expected(app), classes))
  }

  @Test
  fun runtimeFailuresNeverBecomeApplicationBugsEvenWhenExpected() {
    val failures =
        listOf(
            NoClassDefFoundError("expected"),
            StackOverflowError("expected"),
            OutOfMemoryError("expected"),
            ThreadDeath(),
            CancellationException("expected"),
        )
    for (failure in failures) {
      failure.stackTrace = arrayOf(frame)
      // ThreadDeath has no message; a nonempty expected prefix remains intentionally unmatched.
      val classification = classifyExpectedFailure(failure, expected(failure), classes)
      assertNull(classification, failure.javaClass.name)
    }
    assertEquals(5, failures.size)
  }
}
