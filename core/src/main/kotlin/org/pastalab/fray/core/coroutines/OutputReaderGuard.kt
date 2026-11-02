package org.pastalab.fray.core.coroutines

import java.util.concurrent.atomic.AtomicBoolean

/** Record capture failure even when the reader terminates with an Error. */
internal inline fun guardOutputReader(failed: AtomicBoolean, action: () -> Unit) {
  try {
    action()
  } catch (failure: Throwable) {
    failed.set(true)
    // Preserve the reader's existing treatment of ordinary exceptions, but do not suppress Errors
    // or other non-Exception Throwables.
    if (failure !is Exception) throw failure
  }
}
