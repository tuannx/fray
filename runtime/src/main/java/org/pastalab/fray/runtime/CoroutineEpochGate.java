package org.pastalab.fray.runtime;

/**
 * Experimental common barrier for one owned coroutine epoch. Every job and task hook must hold
 * monitor() and call requireOpen() before mutating its own tracker. A failed or late event taints
 * the process; a caller must discard the isolated process instead of resetting a failed epoch.
 */
public final class CoroutineEpochGate {
  private static final String SKIP = "coroutine-epoch-gate";
  private static final Object LOCK = new Object();
  private static ClassLoader owner;
  private static boolean sealed;
  private static boolean broken;
  private static boolean staleEvent;
  private static Throwable permittedRootFailure;

  private CoroutineEpochGate() {}

  public static Object monitor() {
    return LOCK;
  }

  /** Must run before creating a tracked coroutine job or dispatching a task. */
  public static void begin(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (LOCK) {
        if (loader == null
            || owner != null
            || staleEvent
            || !CoroutineInstrumentationOwners.owns(loader)) {
          throw new IllegalStateException("coroutine epoch gate is not uniquely owned");
        }
        owner = loader;
        sealed = false;
        broken = false;
        permittedRootFailure = null;
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Hook caller must already hold monitor(); otherwise there is no atomicity guarantee. */
  public static void requireOpen(ClassLoader loader) {
    if (!Thread.holdsLock(LOCK))
      throw new IllegalStateException("coroutine epoch gate lock not held");
    if (owner == null) {
      staleEvent = true;
      throw new IllegalStateException("coroutine event outside an active epoch");
    }
    if (loader != owner || sealed || broken) {
      broken = true;
      throw new IllegalStateException("late, foreign or invalid coroutine epoch event");
    }
  }

  private static void requireSealed(ClassLoader loader) {
    if (owner == null || owner != loader || !sealed || broken) {
      throw new IllegalStateException("coroutine epoch is not safely sealed");
    }
  }

  /** Used only while holding monitor() to recognize the restricted empty shutdown poll. */
  static boolean isSealedForShutdown(ClassLoader loader) {
    if (!Thread.holdsLock(LOCK))
      throw new IllegalStateException("coroutine epoch gate lock not held");
    return owner != null && owner == loader && sealed && !broken;
  }

  /** A violated shutdown invariant permanently taints the current isolated process. */
  static void rejectShutdownEvent(String message) {
    if (!Thread.holdsLock(LOCK))
      throw new IllegalStateException("coroutine epoch gate lock not held");
    if (owner == null) staleEvent = true;
    else broken = true;
    throw new IllegalStateException(message);
  }

  /** Atomically verify job state and task counters before closing admission to both. */
  public static String atomicSeal(ClassLoader loader) {
    return atomicSeal(loader, null);
  }

  /** Only the exact caller-observed root failure may be admitted, and remains pinned until end. */
  public static String atomicSeal(ClassLoader loader, Throwable allowedRootFailure) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (LOCK) {
        requireOpen(loader);
        String jobs = CoroutineJobTracker.verifyBoundary(loader, allowedRootFailure);
        String tasks = CoroutineTaskTracker.sealUnderGate(loader);
        permittedRootFailure = allowedRootFailure;
        sealed = true;
        return jobs + "; " + tasks;
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Recheck after dispatcher shutdown and verified termination of owned workers/callbacks. */
  public static String verifySealed(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (LOCK) {
        requireSealed(loader);
        return CoroutineJobTracker.verifyBoundary(loader, permittedRootFailure)
            + "; "
            + CoroutineTaskTracker.verifySealed(loader);
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** The caller must independently prove owned workers and all callback sources have stopped. */
  public static void endAfterTermination(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (LOCK) {
        requireSealed(loader);
        CoroutineJobTracker.verifyBoundary(loader, permittedRootFailure);
        CoroutineTaskTracker.verifySealed(loader);
        try {
          CoroutineJobTracker.endAfterTermination(loader);
          CoroutineTaskTracker.endAfterTermination(loader);
          owner = null;
          sealed = false;
          permittedRootFailure = null;
        } catch (Throwable failure) {
          broken = true;
          throw failure;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }
}
