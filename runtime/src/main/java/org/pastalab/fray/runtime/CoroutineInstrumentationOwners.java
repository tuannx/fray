package org.pastalab.fray.runtime;

import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Experimental ownership gate for instrumentation of an iteration-private coroutine library. */
public final class CoroutineInstrumentationOwners {
  private static final Object LOCK = new Object();
  private static WeakReference<ClassLoader> owner;
  private static String failure;
  private static final Set<String> transformed = new HashSet<>();
  // Lab-only app handshake. It verifies the input observed by Fray's transformer and its own
  // successful callback. It cannot detect a later transformer; the worker must remain unsupported
  // until a trusted, fixed agent/JVMTI chain and no dynamic attachment are independently enforced.
  private static Map<String, String> appExpected;
  private static final Set<String> appStarted = new HashSet<>();
  private static final Map<String, String> appFinished = new HashMap<>();
  private static final Set<String> appChecked = new HashSet<>();
  private static final Set<String> appDefinitions = new HashSet<>();
  private static final Set<String> cachedResolutions = new HashSet<>();
  private static boolean strictSealed;
  private static final Map<String, String> coroutineExpected = new HashMap<>();
  private static final Set<String> coroutineDefinitions = new HashSet<>();
  private static final Set<String> coroutineInputVerified = new HashSet<>();
  private static final Set<String> coroutineChecked = new HashSet<>();

  private CoroutineInstrumentationOwners() {}

  /** Only one epoch may own the process-wide Fray delegates at a time. */
  public static void register(ClassLoader loader) {
    if (loader == null) throw new IllegalArgumentException("bootstrap loader cannot own an epoch");
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        if (owner != null && owner.get() != null) {
          throw new IllegalStateException("a coroutine instrumentation owner is already active");
        }
        owner = new WeakReference<>(loader);
        failure = null;
        transformed.clear();
        appExpected = null;
        appStarted.clear();
        appFinished.clear();
        appChecked.clear();
        appDefinitions.clear();
        cachedResolutions.clear();
        strictSealed = false;
        coroutineExpected.clear();
        coroutineDefinitions.clear();
        coroutineInputVerified.clear();
        coroutineChecked.clear();
        // Publish only after the replacement owner and its empty graph state are initialized.
        Runtime.coroutineOwnerPublished = true;
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Register the complete frozen app class graph before defining any of its classes. */
  public static void registerAppGraph(ClassLoader loader, Map<String, String> expectedSha256) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (appExpected != null
            || expectedSha256 == null
            || expectedSha256.isEmpty()
            || expectedSha256.size() > 1000) {
          fail("invalid or duplicate app graph");
        }
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : expectedSha256.entrySet()) {
          String name = entry.getKey();
          String digest = entry.getValue();
          if (name == null
              || name.isEmpty()
              || name.indexOf('.') >= 0
              || digest == null
              || !digest.matches("[0-9a-f]{64}")) {
            fail("invalid app graph entry");
          }
          copy.put(name, digest);
        }
        appExpected = copy;
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  public static boolean hasAppGraph(ClassLoader loader) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        return loader != null && owner != null && owner.get() == loader && appExpected != null;
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  public static boolean expectsApp(ClassLoader loader, String name) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        return loader != null
            && owner != null
            && owner.get() == loader
            && appExpected != null
            && appExpected.containsKey(name);
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Reserve an app definition under the same lock used by the final seal. */
  public static void beginAppDefinition(ClassLoader loader, String name) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || appExpected == null
            || !appExpected.containsKey(name)
            || appDefinitions.contains(name)
            || appStarted.contains(name)
            || appFinished.containsKey(name)
            || appChecked.contains(name)) {
          fail("invalid or late app definition: " + name);
        }
        appDefinitions.add(name);
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Release only after defineClass and its one-use transformer check both succeed. */
  public static void finishAppDefinition(ClassLoader loader, String name) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || !appDefinitions.contains(name)
            || !appChecked.contains(name)
            || appStarted.contains(name)
            || appFinished.containsKey(name)) {
          fail("invalid app definition completion: " + name);
        }
        appDefinitions.remove(name);
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Reserve linking of an already-defined owned class while the caller invokes resolveClass. */
  public static void beginCachedResolution(ClassLoader loader, String name) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        boolean completedApp =
            appExpected != null
                && appExpected.containsKey(name)
                && appChecked.contains(name)
                && !appDefinitions.contains(name);
        boolean completedCoroutine =
            appExpected != null
                && name != null
                && name.startsWith("kotlinx/coroutines/")
                && coroutineChecked.contains(name)
                && !coroutineDefinitions.contains(name);
        if (strictSealed || !(completedApp || completedCoroutine) || !cachedResolutions.add(name)) {
          fail("invalid or late cached resolution: " + name);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  public static void finishCachedResolution(ClassLoader loader, String name) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed || !cachedResolutions.remove(name)) {
          fail("invalid cached resolution completion: " + name);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Must be the first owned-app transformer callback and see the exact frozen input bytes. */
  public static void verifyAppInput(ClassLoader loader, String name, byte[] bytes) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || appExpected == null
            || !appExpected.containsKey(name)
            || !appDefinitions.contains(name)
            || bytes == null
            || appStarted.contains(name)
            || appFinished.containsKey(name)
            || appChecked.contains(name)
            || !appExpected.get(name).equals(sha256(bytes))) {
          fail("invalid, repeated or altered app transformation input: " + name);
        }
        appStarted.add(name);
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Called only after Fray's own app transform succeeded and its output name was checked. */
  public static void markAppTransformed(ClassLoader loader, String name, byte[] bytes) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || appExpected == null
            || !appExpected.containsKey(name)
            || !appDefinitions.contains(name)
            || !appStarted.remove(name)
            || bytes == null
            || appFinished.containsKey(name)
            || appChecked.contains(name)) {
          fail("invalid app transformation acknowledgement: " + name);
        }
        appFinished.put(name, sha256(bytes));
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** One-use post-define check. It proves Fray's callback, not bytes from any later transformer. */
  public static void checkAppTransformed(ClassLoader loader, String name) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || appExpected == null
            || !appExpected.containsKey(name)
            || !appDefinitions.contains(name)
            || appStarted.contains(name)
            || appFinished.remove(name) == null
            || !appChecked.add(name)) {
          fail("missing or repeated app transformation: " + name);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /**
   * Opt-in final boundary only: caller must first stop all class/callback sources and workers.
   * Loading may legitimately nest, so this is never called by ordinary checkHealthy or close. On
   * success the shared lock seals admission to new app/coroutine definitions and callbacks.
   */
  public static void checkStrictBoundary(ClassLoader loader, String requiredAppClass) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || appExpected == null
            || requiredAppClass == null
            || !appExpected.containsKey(requiredAppClass)
            || !appChecked.contains(requiredAppClass)
            || !appDefinitions.isEmpty()
            || !cachedResolutions.isEmpty()
            || !appStarted.isEmpty()
            || !appFinished.isEmpty()
            || !coroutineExpected.isEmpty()
            || !coroutineDefinitions.isEmpty()
            || !coroutineInputVerified.isEmpty()
            || !coroutineChecked.containsAll(transformed)) {
          fail("strict app/coroutine transform boundary is incomplete");
        }
        strictSealed = true;
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  private static void requireHealthyOwner(ClassLoader loader) {
    if (owner == null || owner.get() != loader || loader == null) {
      throw new IllegalStateException("coroutine instrumentation owner is not active");
    }
    if (failure != null)
      throw new IllegalStateException("coroutine instrumentation failed: " + failure);
  }

  private static void fail(String message) {
    if (failure == null) failure = message;
    throw new IllegalStateException(message);
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        int unsigned = value & 0xff;
        result.append(Character.forDigit(unsigned >>> 4, 16));
        result.append(Character.forDigit(unsigned & 15, 16));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      fail("SHA-256 is unavailable");
      throw new AssertionError(impossible);
    }
  }

  public static void markTransformed(ClassLoader loader, String className) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        if (owner == null || owner.get() != loader) {
          throw new IllegalStateException("coroutine instrumentation owner mismatch");
        }
        if ((appExpected != null && (strictSealed || !coroutineInputVerified.remove(className)))
            || !transformed.add(className)) {
          fail("missing or repeated coroutine transformation input: " + className);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Strict graph loader declares the exact frozen coroutine bytes before invoking defineClass. */
  public static void expectCoroutineInput(ClassLoader loader, String name, byte[] bytes) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || appExpected == null
            || name == null
            || !name.startsWith("kotlinx/coroutines/")
            || bytes == null
            || coroutineExpected.containsKey(name)
            || coroutineDefinitions.contains(name)
            || transformed.contains(name)) {
          fail("invalid or repeated frozen coroutine input: " + name);
        }
        coroutineExpected.put(name, sha256(bytes));
        coroutineDefinitions.add(name);
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Release only after coroutine define, acknowledgement and requested resolution complete. */
  public static void finishCoroutineDefinition(ClassLoader loader, String name) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        if (strictSealed
            || appExpected == null
            || !coroutineDefinitions.contains(name)
            || !coroutineChecked.contains(name)
            || coroutineExpected.containsKey(name)
            || coroutineInputVerified.contains(name)) {
          fail("invalid coroutine definition completion: " + name);
        }
        coroutineDefinitions.remove(name);
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** The coroutine transformer must observe exactly the input reserved by the frozen loader. */
  public static void verifyCoroutineInput(ClassLoader loader, String name, byte[] bytes) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        requireHealthyOwner(loader);
        String expected = coroutineExpected.remove(name);
        if (strictSealed
            || appExpected == null
            || expected == null
            || bytes == null
            || coroutineInputVerified.contains(name)
            || transformed.contains(name)
            || !expected.equals(sha256(bytes))) {
          fail("altered or unreserved coroutine input: " + name);
        }
        coroutineInputVerified.add(name);
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** A missing transformer must fail closed even when the JVM suppresses transformer errors. */
  public static void checkTransformed(ClassLoader loader, String className) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        if (owner == null || owner.get() != loader) {
          throw new IllegalStateException("coroutine instrumentation owner mismatch");
        }
        if (failure == null && !transformed.contains(className)) {
          failure = "missing transform: " + className;
        }
        if (failure != null) {
          throw new IllegalStateException(
              "coroutine class was not safely instrumented: " + className);
        }
        if (appExpected != null && (strictSealed || !coroutineChecked.add(className))) {
          fail("repeated coroutine transformation check: " + className);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  public static boolean owns(ClassLoader loader) {
    if (loader == null) return false;
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        return owner != null && owner.get() == loader;
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** Transformer exceptions do not abort JVM class loading; retain them for the owning loader. */
  public static void markFailure(ClassLoader loader, String message) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        if (owner != null && owner.get() == loader && failure == null) failure = message;
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  public static void checkHealthy(ClassLoader loader) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        if (owner == null || owner.get() != loader) {
          throw new IllegalStateException("coroutine instrumentation owner is not active");
        }
        if (failure != null) {
          throw new IllegalStateException("coroutine instrumentation failed: " + failure);
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }

  /** The lifecycle owner must first stop its workers and prove no callbacks remain. */
  public static void unregister(ClassLoader loader) {
    Runtime.onSkipPrimitive("coroutine instrumentation ownership");
    try {
      synchronized (LOCK) {
        if (owner == null || owner.get() != loader) {
          throw new IllegalStateException("coroutine instrumentation owner mismatch");
        }
        owner.clear();
        owner = null;
        failure = null;
        transformed.clear();
        appExpected = null;
        appStarted.clear();
        appFinished.clear();
        appChecked.clear();
        appDefinitions.clear();
        cachedResolutions.clear();
        strictSealed = false;
        coroutineExpected.clear();
        coroutineDefinitions.clear();
        coroutineInputVerified.clear();
        coroutineChecked.clear();
        // Callers must stop all definitions/callbacks before unregistering the owner.
        Runtime.coroutineOwnerPublished = false;
      }
    } finally {
      Runtime.onSkipPrimitiveDone("coroutine instrumentation ownership");
    }
  }
}
