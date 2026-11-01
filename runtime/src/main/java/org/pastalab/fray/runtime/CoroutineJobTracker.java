package org.pastalab.fray.runtime;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Experimental, fail-closed bookkeeping for a single isolated coroutine runtime. It is not by
 * itself a proof of dispatcher quiescence or host isolation.
 */
public final class CoroutineJobTracker {
  private static final String SKIP = "coroutine-job-tracker";
  private static final int MAX_JOBS = 100_000;
  private static volatile Epoch current;
  private static int lateCallbacks;

  private CoroutineJobTracker() {}

  private static final class JobState {
    boolean constructed;
    boolean classified;
    boolean finalizing;
    boolean finalized;
    boolean root;
    Throwable failure;
  }

  private static final class Epoch {
    final ClassLoader loader;
    final Class<?> rootClass;
    final Thread owner;
    final Map<Object, JobState> jobs = new IdentityHashMap<>();
    int roots;
    int constructing;
    int pending;
    int finalizing;
    int violations;
    int cancellations;
    int failures;

    Epoch(ClassLoader loader, Class<?> rootClass, Thread owner) {
      this.loader = loader;
      this.rootClass = rootClass;
      this.owner = owner;
    }
  }

  public static void begin(ClassLoader loader, Class<?> rootClass, Thread owner) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        CoroutineEpochGate.requireOpen(loader);
        if (current != null) throw new IllegalStateException("coroutine epoch already active");
        if (lateCallbacks != 0)
          throw new IllegalStateException("late coroutine callback after prior epoch");
        if (loader == null
            || rootClass.getClassLoader() != loader
            || owner == null
            || !rootClass.getName().equals("kotlinx.coroutines.BlockingCoroutine")) {
          throw new IllegalArgumentException("unowned coroutine root");
        }
        current = new Epoch(loader, rootClass, owner);
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Hooks are emitted only into an owned JobSupport class, never the host's class. */
  private static boolean belongsToEpoch(Epoch epoch, Object job) {
    if (epoch == null) {
      lateCallbacks++;
      return false;
    }
    if (job == null || job.getClass().getClassLoader() != epoch.loader) {
      synchronized (epoch) {
        epoch.violations++;
      }
      return false;
    }
    return true;
  }

  /** Begins immediately after JobSupport's Object constructor, before its fields can schedule. */
  public static void constructingEnter(Object job) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        CoroutineEpochGate.requireOpen(job == null ? null : job.getClass().getClassLoader());
        Epoch epoch = current;
        if (!belongsToEpoch(epoch, job)) return;
        synchronized (epoch) {
          if (epoch.jobs.size() >= MAX_JOBS || epoch.jobs.containsKey(job)) {
            epoch.violations++;
            return;
          }
          epoch.jobs.put(job, new JobState());
          epoch.constructing++;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Normal constructor return. An exceptional constructor remains counted and fails closed. */
  public static void created(Object job) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        CoroutineEpochGate.requireOpen(job == null ? null : job.getClass().getClassLoader());
        Epoch epoch = current;
        if (!belongsToEpoch(epoch, job)) return;
        synchronized (epoch) {
          JobState state = epoch.jobs.get(job);
          if (state == null || state.constructed || epoch.constructing <= 0) {
            epoch.violations++;
            return;
          }
          state.constructed = true;
          epoch.constructing--;
          epoch.pending++;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Called only on normal return from JobSupport.initParentJob. */
  public static void parentInitialized(Object job, Object parent) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        CoroutineEpochGate.requireOpen(job == null ? null : job.getClass().getClassLoader());
        Epoch epoch = current;
        if (!belongsToEpoch(epoch, job)) return;
        synchronized (epoch) {
          JobState state = epoch.jobs.get(job);
          if (state == null || !state.constructed || state.classified) {
            epoch.violations++;
            return;
          }
          state.classified = true;
          epoch.pending--;
          if (parent == null) {
            if (job.getClass() == epoch.rootClass
                && Thread.currentThread() == epoch.owner
                && ++epoch.roots == 1) {
              state.root = true;
              return;
            }
            epoch.violations++;
            return;
          }
          if (!epoch.jobs.containsKey(parent)) {
            epoch.violations++;
            return;
          }
          try {
            Method getParent = job.getClass().getMethod("getParent");
            getParent.setAccessible(true);
            if (getParent.invoke(job) != parent) epoch.violations++;
          } catch (ReflectiveOperationException | RuntimeException e) {
            // An already completed/ambiguous attachment is not accepted as proof of ownership.
            epoch.violations++;
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  public static void finalizationEnter(Object job) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        CoroutineEpochGate.requireOpen(job == null ? null : job.getClass().getClassLoader());
        Epoch epoch = current;
        if (!belongsToEpoch(epoch, job)) return;
        synchronized (epoch) {
          JobState state = epoch.jobs.get(job);
          if (state == null || state.finalizing || state.finalized) {
            epoch.violations++;
            return;
          }
          state.finalizing = true;
          epoch.finalizing++;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Called on every normal or exceptional exit after completion callbacks. */
  public static void finalizationExit(Object job, Object finalState, boolean threw) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        CoroutineEpochGate.requireOpen(job == null ? null : job.getClass().getClassLoader());
        Epoch epoch = current;
        if (!belongsToEpoch(epoch, job)) return;
        synchronized (epoch) {
          JobState state = epoch.jobs.get(job);
          if (state == null || !state.finalizing) {
            epoch.violations++;
            return;
          }
          state.finalizing = false;
          epoch.finalizing--;
          if (threw) {
            epoch.violations++;
            return;
          }
          state.finalized = true;
          Class<?> exceptionalType = null;
          for (Class<?> type = finalState == null ? null : finalState.getClass();
              type != null;
              type = type.getSuperclass()) {
            if (type.getName().equals("kotlinx.coroutines.CompletedExceptionally")) {
              if (type.getClassLoader() != epoch.loader) epoch.violations++;
              else exceptionalType = type;
              break;
            }
          }
          if (exceptionalType != null) {
            try {
              Object cause = exceptionalType.getField("cause").get(finalState);
              if (cause instanceof CancellationException) epoch.cancellations++;
              else if (cause instanceof Throwable) {
                state.failure = (Throwable) cause;
                epoch.failures++;
              } else epoch.violations++;
            } catch (ReflectiveOperationException | RuntimeException e) {
              epoch.violations++;
            }
          }
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** A successful return checks only tracked jobs, not unknown callbacks or worker teardown. */
  public static String verifyBoundary(ClassLoader loader) {
    return verifyBoundary(loader, null);
  }

  /** Permit only the exact root failure returned by the workload; all other failures reject. */
  public static String verifyBoundary(ClassLoader loader, Throwable allowedRootFailure) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        Epoch epoch = current;
        if (epoch == null || epoch.loader != loader)
          throw new IllegalStateException("unknown coroutine epoch");
        synchronized (epoch) {
          int unfinished = 0;
          int allowedFailures = 0;
          for (JobState state : epoch.jobs.values()) {
            if (!state.finalized) unfinished++;
            if (state.root && state.failure != null && state.failure == allowedRootFailure)
              allowedFailures++;
          }
          String result =
              "roots="
                  + epoch.roots
                  + ", unfinished="
                  + unfinished
                  + ", constructing="
                  + epoch.constructing
                  + ", pending="
                  + epoch.pending
                  + ", finalizing="
                  + epoch.finalizing
                  + ", violations="
                  + epoch.violations
                  + ", cancellations="
                  + epoch.cancellations
                  + ", failures="
                  + epoch.failures;
          if (epoch.roots != 1
              || unfinished != 0
              || epoch.constructing != 0
              || epoch.pending != 0
              || epoch.finalizing != 0
              || epoch.violations != 0
              || (allowedRootFailure == null
                  ? epoch.failures != 0
                  : epoch.failures != 1 || allowedFailures != 1)) {
            throw new IllegalStateException("unsupported coroutine job state: " + result);
          }
          return result;
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }

  /** Caller must first prove all owned workers/callbacks have terminated. */
  public static void endAfterTermination(ClassLoader loader) {
    Runtime.onSkipPrimitive(SKIP);
    try {
      synchronized (CoroutineEpochGate.monitor()) {
        if (current == null || current.loader != loader)
          throw new IllegalStateException("unknown coroutine epoch");
        current = null;
      }
    } finally {
      Runtime.onSkipPrimitiveDone(SKIP);
    }
  }
}
