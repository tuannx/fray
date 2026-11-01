package org.pastalab.fray.core.coroutines

/** Local bounded diagnostic: no exception messages, user values or filesystem paths. */
internal object CooperativeDiagnostics {
  fun failure(error: Throwable) {
    System.err.println("COOPERATIVE_FAILURE ${error.javaClass.name.take(256)}")
    error.stackTrace.take(24).forEach {
      System.err.println("  ${it.className.take(256)}.${it.methodName.take(256)}:${it.lineNumber}")
    }
  }
}
