package org.pastalab.fray.core.observers

import org.pastalab.fray.core.RunContext

/**
 * Diagnostic only. Caller labels come from stack frames and are observations, not ownership proof.
 */
object ExperimentalParkTrace {
  val enabled = java.lang.Boolean.getBoolean("fray.coroutine.experiment.mutexTrace")
  private const val capacity = 200
  private val events = java.util.ArrayDeque<String>(capacity)
  private var sequence = 0L

  @JvmStatic
  fun reset() {
    if (!enabled) return
    synchronized(events) {
      events.clear()
      sequence = 0
    }
  }

  fun event(context: RunContext, event: String, detail: String = "", caller: Boolean = false) {
    if (!enabled) return
    // Called from within an existing delegate guard; only the diagnostic's own operations are
    // skipped.
    try {
      val thread = Thread.currentThread()
      val site =
          if (caller) {
            val frames = thread.stackTrace
            when {
              frames.any {
                it.className == "kotlinx.coroutines.BlockingCoroutine" &&
                    it.methodName == "joinBlocking"
              } -> "root-joinBlocking"
              frames.any {
                it.className == "kotlinx.coroutines.scheduling.CoroutineScheduler\u0024Worker" &&
                    it.methodName == "park"
              } -> "worker-idle-park"
              frames.any {
                it.className == "kotlinx.coroutines.scheduling.CoroutineScheduler\u0024Worker" &&
                    it.methodName == "runWorker"
              } -> "worker-runWorker-other"
              else -> "other"
            }
          } else "unclassified"
      val index = context.registeredThreads[thread.id]?.index ?: -1
      val now = context.timeController.currentTimeMillisRawNoIncrement()
      val virtualNanos =
          if (context.timeController.isVirtualTimeMode) context.timeController.nanoTime else -1L
      synchronized(events) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(
            "${sequence++} step=${context.step} thread=$index event=$event caller=$site nowMillis=$now virtualNanos=$virtualNanos $detail"
        )
      }
    } catch (_: Exception) {
      // Diagnostic failures cannot change scheduling semantics.
    }
  }

  @JvmStatic
  fun dump(reason: String) {
    if (!enabled) return
    val snapshot = synchronized(events) { events.toList() }
    System.err.println(
        "MUTEX_PARK_BEGIN $reason (bounded observations; stack labels are approximate)"
    )
    snapshot.forEach { System.err.println("MUTEX_PARK $it") }
    System.err.println("MUTEX_PARK_END $reason")
    System.err.flush()
  }
}
