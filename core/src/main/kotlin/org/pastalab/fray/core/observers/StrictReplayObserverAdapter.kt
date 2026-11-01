package org.pastalab.fray.core.observers

import java.nio.file.Path
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver

/** Experiment-only exact-delegate adapter. A null delegate is the recording-mode no-op. */
class StrictReplayObserverAdapter(private val delegate: StrictReplayVerifier?) :
    ScheduleObserver<ThreadContext>, TestStatusObserver {
  internal fun delegatesTo(verifier: StrictReplayVerifier): Boolean = delegate === verifier

  override fun onExecutionStart() {
    delegate?.onExecutionStart()
  }

  override fun onReportError(throwable: Throwable) {
    delegate?.onReportError(throwable)
  }

  override fun onExecutionDone(bugFound: Throwable?) {
    delegate?.onExecutionDone(bugFound)
  }

  override fun saveToReportFolder(path: Path) {
    delegate?.saveToReportFolder(path)
  }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {
    delegate?.onContextSwitch(current, next)
  }

  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    delegate?.onNewSchedule(allThreads, scheduled)
  }
}
