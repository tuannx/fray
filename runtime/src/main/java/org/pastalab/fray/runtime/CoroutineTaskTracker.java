package org.pastalab.fray.runtime;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Experimental quiescence accounting for the pinned, owned CoroutineScheduler only. This does not
 * account for arbitrary executors, callbacks, native work or escaped continuations. Direct or
 * reflective calls to scheduler internals are unsupported: task identity alone does not prove that
 * a runSafely call originated from the corresponding scheduler queue publication.
 */
public final class CoroutineTaskTracker {
  private static final String SKIP = "coroutine-task-tracker";
  private static final long MAX_OUTSTANDING = 100_000;
  private static final boolean TRACE_VIOLATIONS =
      Boolean.getBoolean("fray.coroutine.experiment.mutexTrace");
  private static volatile Epoch current;

  private CoroutineTaskTracker() {}

  private static final class Epoch {
    final ClassLoader loader;
    final Thread ownerThread;
    Object scheduler;
    Object eventLoop;
    final Map<Thread, ArrayDeque<Dispatch>> dispatchStacks = new IdentityHashMap<>();
    // A Task identity can be dispatched again before its earlier runSafely returns.
    // Multiple registered-but-unstarted generations would be ambiguous and are rejected.
    final Map<Object, ArrayDeque<Dispatch>> tasks = new IdentityHashMap<>();
    final Map<Thread, ArrayDeque<Dispatch>> runStacks = new IdentityHashMap<>();
    final Map<Thread, ArrayDeque<Dispatch>> enqueueStacks = new IdentityHashMap<>();
    final Map<Object, Dispatch> eventTasks = new IdentityHashMap<>();
    long outstanding;
    int registeredGenerations;
    int dispatching;
    int running;
    int violations;
    String firstViolation;
    boolean firstViolationAttempted;
    int taskFailures;
    boolean sealed;
    boolean shutdownPollActive;

    Epoch(ClassLoader loader) {
      this.loader = loader;
      this.ownerThread = Thread.currentThread();
    }
  }

  private static final class Dispatch {
    Object task;
    boolean ended;
    boolean started;
    boolean finished;
  }

  /** Caller holds the epoch lock; failed diagnostics must not alter violation counts or flow. */
  private static void recordViolation(Epoch epoch) {
    epoch.violations++;
    if (!TRACE_VIOLATIONS || epoch.firstViolationAttempted) return;
    epoch.firstViolationAttempted = true;
    try {
      StackTraceElement[] stack = Thread.currentThread().getStackTrace();
      StringBuilder evidence = new StringBuilder();
      for (int i = 1; i < Math.min(stack.length, 17); i++) {
        if (evidence.length() != 0) evidence.append(" > ");
        StackTraceElement frame = stack[i];
        evidence
            .append(frame.getClassName())
            .append('#')
            .append(frame.getMethodName())
            .append(':')
            .append(frame.getLineNumber());
      }
      epoch.firstViolation = evidence.toString();
    } catch (Throwable ignored) {
      // Preserve the original bookkeeping and failure path even if tracing fails.
    }
  }

  /** Diagnostic-only predicate values for the first registerTask rejection. */
  private static void recordRegisterViolation(
      Epoch epoch, Object scheduler, Object task, ArrayDeque<Dispatch> stack, Dispatch dispatch) {
    recordViolation(epoch);
    if (!TRACE_VIOLATIONS || epoch.violations != 1) return;
    try {
      ArrayDeque<Dispatch> previous = task == null ? null : epoch.tasks.get(task);
      int pending = 0;
      int running = 0;
      if (previous != null)
        for (Dispatch generation : previous) {
          if (!generation.started) pending++;
          if (generation.started && !generation.finished) running++;
        }
      epoch.firstViolation =
          "registerTask[schedulerMatch="
              + (epoch.scheduler == scheduler)
              + ", taskPresent="
              + (task != null)
              + ", stackPresent="
              + (stack != null && !stack.isEmpty())
              + ", dispatchPresent="
              + (dispatch != null)
              + ", dispatchAlreadyAssigned="
              + (dispatch != null && dispatch.task != null)
              + ", sameTaskRegistered="
              + (previous != null)
              + ", sameTaskPending="
              + pending
              + ", sameTaskRunning="
              + running
              + "] stack="
              + (epoch.firstViolation == null ? "unavailable" : epoch.firstViolation);
    } catch (Throwable ignored) {
      // Preserve the original bookkeeping and failure path even if tracing fails.
    }
  }

  public static void begin(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        CoroutineEpochGate.requireOpen(loader);
        synchronized (CoroutineTaskTracker.class) {
          if (current != null || loader == null || !CoroutineInstrumentationOwners.owns(loader)) {
            throw new IllegalStateException("coroutine task epoch is not uniquely owned");
          }
          current = new Epoch(loader);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  private static Epoch epochFor(Object instance) {
    Epoch epoch = current;
    if (epoch == null) throw new IllegalStateException("no coroutine task epoch");
    if (instance == null || instance.getClass().getClassLoader() != epoch.loader) {
      synchronized (epoch) {
        recordViolation(epoch);
      }
      throw new IllegalStateException("foreign coroutine scheduler or event loop");
    }
    return epoch;
  }

  private static Epoch openEpochFor(Object instance) {
    CoroutineEpochGate.requireOpen(instance == null ? null : instance.getClass().getClassLoader());
    return epochFor(instance);
  }

  /** Reserve before publication: a worker may execute before dispatch returns. */
  public static void dispatchBegin(Object scheduler) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(scheduler);
        synchronized (epoch) {
          if (epoch.scheduler == null) epoch.scheduler = scheduler;
          if (epoch.sealed
              || epoch.scheduler != scheduler
              || epoch.outstanding >= MAX_OUTSTANDING) {
            recordViolation(epoch);
            throw new IllegalStateException("unsupported or late coroutine dispatch");
          }
          try {
            Thread thread = Thread.currentThread();
            ArrayDeque<Dispatch> stack = epoch.dispatchStacks.get(thread);
            if (stack == null) {
              stack = new ArrayDeque<>();
              epoch.dispatchStacks.put(thread, stack);
            }
            stack.push(new Dispatch());
            epoch.outstanding++;
            epoch.dispatching++;
          } catch (Throwable failure) {
            recordViolation(epoch);
            throw failure;
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Called after createTask returns but before its result can be published to a worker. */
  public static void registerTask(Object scheduler, Object task) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(scheduler);
        synchronized (epoch) {
          ArrayDeque<Dispatch> stack = epoch.dispatchStacks.get(Thread.currentThread());
          Dispatch dispatch = stack == null ? null : stack.peek();
          ArrayDeque<Dispatch> generations = task == null ? null : epoch.tasks.get(task);
          boolean pendingExists = false;
          int runningGenerations = 0;
          if (generations != null) {
            for (Dispatch generation : generations) {
              if (!generation.started) pendingExists = true;
              if (generation.started && !generation.finished) runningGenerations++;
            }
          }
          if (epoch.scheduler != scheduler
              || task == null
              || dispatch == null
              || dispatch.task != null
              || pendingExists
              || runningGenerations > 1
              || epoch.registeredGenerations >= MAX_OUTSTANDING) {
            recordRegisterViolation(epoch, scheduler, task, stack, dispatch);
            throw new IllegalStateException("untracked or duplicate coroutine task");
          }
          try {
            if (generations == null) {
              generations = new ArrayDeque<>();
              epoch.tasks.put(task, generations);
            }
            generations.addLast(dispatch);
            dispatch.task = task;
            epoch.registeredGenerations++;
          } catch (Throwable failure) {
            recordViolation(epoch);
            throw failure;
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** An exceptional dispatch is ambiguous: it may already have published a task. */
  public static void dispatchEnd(Object scheduler, boolean threw) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(scheduler);
        synchronized (epoch) {
          Thread thread = Thread.currentThread();
          ArrayDeque<Dispatch> stack = epoch.dispatchStacks.get(thread);
          Dispatch dispatch = stack == null ? null : stack.poll();
          if (stack != null && stack.isEmpty()) epoch.dispatchStacks.remove(thread);
          if (epoch.scheduler != scheduler || epoch.dispatching <= 0 || dispatch == null) {
            recordViolation(epoch);
          } else {
            epoch.dispatching--;
            dispatch.ended = true;
            if (dispatch.task == null) recordViolation(epoch);
            if (dispatch.finished) retireGeneration(epoch, dispatch);
          }
          if (threw) recordViolation(epoch);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  public static void runBegin(Object scheduler, Object task) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(scheduler);
        synchronized (epoch) {
          ArrayDeque<Dispatch> generations = epoch.tasks.get(task);
          Dispatch dispatch = null;
          if (generations != null) {
            for (Dispatch candidate : generations) {
              if (!candidate.started) {
                if (dispatch != null) {
                  recordViolation(epoch);
                  throw new IllegalStateException("ambiguous coroutine task generation");
                }
                dispatch = candidate;
              }
            }
          }
          if (epoch.sealed
              || epoch.scheduler != scheduler
              || dispatch == null
              || dispatch.started
              || dispatch.finished
              || epoch.outstanding <= epoch.running) {
            recordViolation(epoch);
            throw new IllegalStateException("untracked or late coroutine task");
          }
          try {
            Thread thread = Thread.currentThread();
            ArrayDeque<Dispatch> runs = epoch.runStacks.get(thread);
            if (runs == null) {
              runs = new ArrayDeque<>();
              epoch.runStacks.put(thread, runs);
            }
            runs.push(dispatch);
            dispatch.started = true;
            epoch.running++;
          } catch (Throwable failure) {
            recordViolation(epoch);
            throw failure;
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  public static void runEnd(Object scheduler, Object task, boolean threw) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(scheduler);
        synchronized (epoch) {
          Thread thread = Thread.currentThread();
          ArrayDeque<Dispatch> runs = epoch.runStacks.get(thread);
          Dispatch dispatch = runs == null ? null : runs.peek();
          if (epoch.scheduler != scheduler
              || dispatch == null
              || !dispatch.started
              || dispatch.task != task
              || dispatch.finished
              || epoch.running <= 0
              || epoch.outstanding <= 0) {
            recordViolation(epoch);
          } else {
            runs.pop();
            if (runs.isEmpty()) epoch.runStacks.remove(thread);
            dispatch.finished = true;
            epoch.running--;
            epoch.outstanding--;
            if (dispatch.ended) retireGeneration(epoch, dispatch);
          }
          if (threw) recordViolation(epoch);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Remove only this completed generation; sibling generations retain their reservations. */
  private static void retireGeneration(Epoch epoch, Dispatch dispatch) {
    ArrayDeque<Dispatch> generations = epoch.tasks.get(dispatch.task);
    if (!dispatch.ended
        || !dispatch.finished
        || generations == null
        || epoch.registeredGenerations <= 0
        || !generations.removeFirstOccurrence(dispatch)) {
      recordViolation(epoch);
      return;
    }
    epoch.registeredGenerations--;
    if (generations.isEmpty()) epoch.tasks.remove(dispatch.task);
  }

  /** Preserve the original Throwable while observing errors swallowed by runSafely. */
  public static void runTask(Object scheduler, Runnable task) {
    try {
      task.run();
    } catch (Throwable original) {
      try {
        Runtime.onSkipPrimitive(SKIP);
        try {
          synchronized (CoroutineEpochGate.monitor()) {
            Epoch epoch = openEpochFor(scheduler);
            synchronized (epoch) {
              epoch.taskFailures++;
            }
          }
        } finally {
          Runtime.onSkipPrimitiveDone(SKIP);
        }
      } catch (Throwable bookkeeping) {
        if (bookkeeping != original) original.addSuppressed(bookkeeping);
      }
      CoroutineTaskTracker.<RuntimeException>throwUnchecked(original);
    }
  }

  /** The restricted mode permits exactly the runBlocking event loop on the epoch owner thread. */
  public static void registerEventLoop(Object loop, Thread thread) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(loop);
        synchronized (epoch) {
          if (!loop.getClass().getName().equals("kotlinx.coroutines.BlockingEventLoop")
              || thread != epoch.ownerThread
              || Thread.currentThread() != epoch.ownerThread
              || epoch.eventLoop != null) {
            recordViolation(epoch);
            throw new IllegalStateException("unsupported coroutine event loop");
          }
          epoch.eventLoop = loop;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  public static void pollEventLoop(Object loop) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        ClassLoader loader = loop == null ? null : loop.getClass().getClassLoader();
        if (CoroutineEpochGate.isSealedForShutdown(loader)) {
          Epoch epoch = epochFor(loop);
          synchronized (epoch) {
            if (Thread.currentThread() != epoch.ownerThread
                || epoch.shutdownPollActive
                || !isEmptyOwnedDefaultExecutor(loop, epoch.loader)) {
              recordViolation(epoch);
              CoroutineEpochGate.rejectShutdownEvent("unsafe or nonempty coroutine shutdown poll");
            }
            epoch.shutdownPollActive = true;
          }
          return;
        }
        Epoch epoch = openEpochFor(loop);
        synchronized (epoch) {
          if (epoch.eventLoop != loop || Thread.currentThread() != epoch.ownerThread) {
            recordViolation(epoch);
            throw new IllegalStateException("unknown coroutine event loop poll");
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Called while both tracker locks and the skip-primitive guard are held. */
  private static boolean isEmptyOwnedDefaultExecutor(Object loop, ClassLoader loader) {
    try {
      Class<?> type = loop.getClass();
      if (type.getClassLoader() != loader
          || !type.getName().equals("kotlinx.coroutines.DefaultExecutor")
          || type.getField("INSTANCE").get(null) != loop) return false;
      Class<?> base = type.getSuperclass();
      if (base == null
          || base.getClassLoader() != loader
          || !base.getName().equals("kotlinx.coroutines.EventLoopImplBase")) return false;
      Method isEmpty = base.getDeclaredMethod("isEmpty");
      if (isEmpty.getReturnType() != boolean.class || !isEmpty.trySetAccessible()) return false;
      return Boolean.TRUE.equals(isEmpty.invoke(loop));
    } catch (ReflectiveOperationException | RuntimeException failure) {
      return false;
    }
  }

  public static void enqueueBegin(Object loop, Object task) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(loop);
        synchronized (epoch) {
          if (epoch.eventLoop != loop
              || task == null
              || epoch.eventTasks.containsKey(task)
              || epoch.outstanding >= MAX_OUTSTANDING) {
            recordViolation(epoch);
            throw new IllegalStateException("unknown or duplicate event-loop task");
          }
          try {
            Dispatch dispatch = new Dispatch();
            dispatch.task = task;
            epoch.eventTasks.put(task, dispatch);
            Thread thread = Thread.currentThread();
            ArrayDeque<Dispatch> stack = epoch.enqueueStacks.get(thread);
            if (stack == null) {
              stack = new ArrayDeque<>();
              epoch.enqueueStacks.put(thread, stack);
            }
            stack.push(dispatch);
            epoch.outstanding++;
            epoch.dispatching++;
          } catch (Throwable failure) {
            recordViolation(epoch);
            throw failure;
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  public static void enqueueEnd(Object loop, Object task, boolean threw) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(loop);
        synchronized (epoch) {
          Thread thread = Thread.currentThread();
          ArrayDeque<Dispatch> stack = epoch.enqueueStacks.get(thread);
          Dispatch dispatch = stack == null ? null : stack.poll();
          if (stack != null && stack.isEmpty()) epoch.enqueueStacks.remove(thread);
          if (epoch.eventLoop != loop
              || epoch.dispatching <= 0
              || dispatch == null
              || dispatch.task != task) {
            recordViolation(epoch);
          } else {
            epoch.dispatching--;
            dispatch.ended = true;
            if (dispatch.finished) epoch.eventTasks.remove(task);
          }
          if (threw) recordViolation(epoch);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Trampoline for processNextEvent's Runnable.run; preserves the original Throwable. */
  public static void runEventLoopTask(Object loop, Runnable task) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(loop);
        synchronized (epoch) {
          Dispatch dispatch = epoch.eventTasks.get(task);
          if (epoch.eventLoop != loop
              || Thread.currentThread() != epoch.ownerThread
              || dispatch == null
              || dispatch.started
              || dispatch.finished
              || epoch.outstanding <= epoch.running) {
            recordViolation(epoch);
            throw new IllegalStateException("untracked event-loop task");
          }
          dispatch.started = true;
          epoch.running++;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
    Throwable failure = null;
    try {
      task.run();
    } catch (Throwable original) {
      failure = original;
    }
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(loop);
        synchronized (epoch) {
          Dispatch dispatch = epoch.eventTasks.get(task);
          if (epoch.eventLoop != loop
              || dispatch == null
              || !dispatch.started
              || dispatch.finished
              || epoch.running <= 0
              || epoch.outstanding <= 0) {
            recordViolation(epoch);
          } else {
            dispatch.finished = true;
            epoch.running--;
            epoch.outstanding--;
            if (dispatch.ended) epoch.eventTasks.remove(task);
          }
          if (failure != null) epoch.taskFailures++;
        }
      }
    } catch (Throwable bookkeeping) {
      if (failure == null) failure = bookkeeping;
      else if (failure != bookkeeping) failure.addSuppressed(bookkeeping);
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
    if (failure != null) CoroutineTaskTracker.<RuntimeException>throwUnchecked(failure);
  }

  /** The unconfined queue is not covered; reject even if an event was already executed. */
  public static void unconfinedProcessed(Object loop, boolean processed) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        ClassLoader loader = loop == null ? null : loop.getClass().getClassLoader();
        if (CoroutineEpochGate.isSealedForShutdown(loader)) {
          Epoch epoch = epochFor(loop);
          synchronized (epoch) {
            if (!epoch.shutdownPollActive
                || Thread.currentThread() != epoch.ownerThread
                || processed
                || !isEmptyOwnedDefaultExecutor(loop, epoch.loader)) {
              recordViolation(epoch);
              CoroutineEpochGate.rejectShutdownEvent("unsupported coroutine shutdown event");
            }
            epoch.shutdownPollActive = false;
          }
          return;
        }
        Epoch epoch = openEpochFor(loop);
        synchronized (epoch) {
          if (processed || epoch.eventLoop != loop || Thread.currentThread() != epoch.ownerThread) {
            recordViolation(epoch);
            throw new IllegalStateException("unsupported unconfined coroutine event");
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void throwUnchecked(Throwable error) throws T {
    throw (T) error;
  }

  /** Timers, delay and fallback dispatch are outside this experimental accounting model. */
  public static void rejectUnsupported(Object eventLoop, String operation) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = openEpochFor(eventLoop);
        synchronized (epoch) {
          recordViolation(epoch);
        }
      }
      throw new IllegalStateException("unsupported coroutine event loop operation: " + operation);
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  private static String status(Epoch epoch) {
    return "outstanding="
        + epoch.outstanding
        + ", registered="
        + epoch.registeredGenerations
        + ", taskIdentities="
        + epoch.tasks.size()
        + ", publishers="
        + (epoch.dispatchStacks.size() + epoch.enqueueStacks.size())
        + ", eventTasks="
        + epoch.eventTasks.size()
        + ", dispatching="
        + epoch.dispatching
        + ", running="
        + epoch.running
        + ", shutdownPollActive="
        + epoch.shutdownPollActive
        + ", violations="
        + epoch.violations
        + ", taskFailures="
        + epoch.taskFailures;
  }

  /**
   * Lab diagnostic only: a synchronized snapshot of tracker bookkeeping, not the scheduler's actual
   * queue contents. It must neither admit work nor taint an epoch on a failed lookup.
   */
  public static String diagnosticSnapshot(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = current;
        if (epoch == null || epoch.loader != loader) return "unavailable: no matching task epoch";
        synchronized (epoch) {
          int registeredNotStarted = 0;
          for (ArrayDeque<Dispatch> generations : epoch.tasks.values()) {
            for (Dispatch task : generations) {
              if (!task.started) registeredNotStarted++;
            }
          }
          int eventNotStarted = 0;
          for (Dispatch task : epoch.eventTasks.values()) {
            if (!task.started) eventNotStarted++;
          }
          return status(epoch)
              + ", registeredNotStarted="
              + registeredNotStarted
              + ", eventNotStarted="
              + eventNotStarted
              + ", sealed="
              + epoch.sealed
              + ", firstViolation="
              + (epoch.firstViolation == null ? "none-or-unavailable" : epoch.firstViolation);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  private static void requireQuiet(Epoch epoch) {
    if (epoch.outstanding != 0
        || epoch.registeredGenerations != 0
        || !epoch.tasks.isEmpty()
        || !epoch.runStacks.isEmpty()
        || !epoch.dispatchStacks.isEmpty()
        || !epoch.eventTasks.isEmpty()
        || !epoch.enqueueStacks.isEmpty()
        || epoch.dispatching != 0
        || epoch.running != 0
        || epoch.shutdownPollActive
        || epoch.violations != 0
        || epoch.taskFailures != 0) {
      throw new IllegalStateException("coroutine task epoch is not quiescent: " + status(epoch));
    }
  }

  /** Called only while the shared job/task gate prevents concurrent callbacks. */
  static String sealUnderGate(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      if (!Thread.holdsLock(CoroutineEpochGate.monitor()))
        throw new IllegalStateException("coroutine gate lock not held");
      Epoch epoch = current;
      if (epoch == null || epoch.loader != loader)
        throw new IllegalStateException("unknown coroutine task epoch");
      synchronized (epoch) {
        if (epoch.sealed) throw new IllegalStateException("coroutine task epoch already sealed");
        requireQuiet(epoch);
        epoch.sealed = true;
        return status(epoch);
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Recheck after worker shutdown: callbacks after seal taint the epoch even if caught. */
  static String verifySealed(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      if (!Thread.holdsLock(CoroutineEpochGate.monitor()))
        throw new IllegalStateException("coroutine gate lock not held");
      Epoch epoch = current;
      if (epoch == null || epoch.loader != loader)
        throw new IllegalStateException("unknown coroutine task epoch");
      synchronized (epoch) {
        if (!epoch.sealed) throw new IllegalStateException("coroutine task epoch was not sealed");
        requireQuiet(epoch);
        return status(epoch);
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Caller must separately prove owned workers and untracked callback sources have stopped. */
  static void endAfterTermination(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      if (!Thread.holdsLock(CoroutineEpochGate.monitor()))
        throw new IllegalStateException("coroutine gate lock not held");
      synchronized (CoroutineTaskTracker.class) {
        Epoch epoch = current;
        if (epoch == null || epoch.loader != loader)
          throw new IllegalStateException("unknown coroutine task epoch");
        synchronized (epoch) {
          if (!epoch.sealed) throw new IllegalStateException("coroutine task epoch was not sealed");
          requireQuiet(epoch);
          current = null;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }
}
