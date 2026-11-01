package org.pastalab.fray.core.utils

class HelperThread : Thread("fray-helper-thread") {
  var shouldStop = false
  var currentRunnable: Runnable? = null
  @Volatile private var retainFailures = false
  @Volatile
  var firstFailure: Throwable? = null
    private set

  init {
    isDaemon = true
  }

  override fun run() {
    while (!shouldStop) {
      val job: Runnable?
      synchronized(this) {
        while (currentRunnable == null && !shouldStop) {
          (this as Object).wait()
        }
        job = currentRunnable
        currentRunnable = null
      }
      try {
        job?.run()
      } catch (e: Throwable) {
        if (retainFailures && firstFailure == null) firstFailure = e
        e.printStackTrace()
      }
    }
  }

  fun submit(runnable: Runnable) {
    synchronized(this) {
      Utils.verifyOrReport({ currentRunnable == null }, "Helper thread is already running a job")
      currentRunnable = runnable
      (this as Object).notify()
    }
  }

  fun stopHelperThread() {
    synchronized(this) {
      shouldStop = true
      (this as Object).notify()
    }
  }

  /** Enable before submitting any jobs; retaining a Throwable is opt-in. */
  fun enableFailureRetention() {
    retainFailures = true
  }

  /** Wait outside Fray's managed execution; zero would mean an unbounded Thread.join. */
  fun awaitTermination(timeoutMillis: Long): Boolean {
    require(timeoutMillis > 0) { "helper join timeout must be positive" }
    join(timeoutMillis)
    return !isAlive
  }
}
