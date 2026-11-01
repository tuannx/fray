package org.pastalab.fray.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.pastalab.fray.core.TestRunner;
import org.pastalab.fray.core.ThreadContext;
import org.pastalab.fray.core.command.Configuration;
import org.pastalab.fray.core.command.ExecutionInfo;
import org.pastalab.fray.core.command.Executor;
import org.pastalab.fray.core.command.FrayClassLoader;
import org.pastalab.fray.core.command.NetworkDelegateType;
import org.pastalab.fray.core.command.SystemTimeDelegateType;
import org.pastalab.fray.core.observers.ReplayFailureFingerprint;
import org.pastalab.fray.core.observers.ScheduleRecording;
import org.pastalab.fray.core.observers.StrictFailureExpectation;
import org.pastalab.fray.core.observers.StrictReplayFiles;
import org.pastalab.fray.core.observers.StrictReplayObserverAdapter;
import org.pastalab.fray.core.observers.StrictReplayVerifier;
import org.pastalab.fray.core.observers.StrictStateCapture;
import org.pastalab.fray.core.observers.StrictStateCapturingScheduler;
import org.pastalab.fray.core.observers.StrictStateFilesV1;
import org.pastalab.fray.core.observers.StrictStateRecorderV1;
import org.pastalab.fray.core.observers.StrictStateReplayVerifierV1;
import org.pastalab.fray.core.observers.StrictStateTraceV1;
import org.pastalab.fray.core.observers.TimelineCoverageType;
import org.pastalab.fray.core.randomness.ControlledRandom;
import org.pastalab.fray.core.randomness.ControlledRandomProvider;
import org.pastalab.fray.core.randomness.Randomness;
import org.pastalab.fray.core.randomness.RandomnessProvider;
import org.pastalab.fray.core.randomness.RecordedRandomProvider;
import org.pastalab.fray.core.randomness.StrictRecordedIntRandomness;
import org.pastalab.fray.core.scheduler.RandomScheduler;
import org.pastalab.fray.core.scheduler.Scheduler;
import org.pastalab.fray.rmi.ScheduleObserver;
import org.pastalab.fray.rmi.TestStatusObserver;
import org.pastalab.fray.runtime.CoroutineEpochGate;
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners;
import org.pastalab.fray.runtime.CoroutineJobTracker;
import org.pastalab.fray.runtime.CoroutineTaskTracker;
import org.pastalab.fray.test.poc.HostCoroutineProbe;

/** Experimental tests: each target runs in a disposable JVM, never in the JUnit host. */
@EnabledIfSystemProperty(named = "fray.coroutine.experiment", matches = "true")
public class CoroutineIsolationExperiment {
  @TempDir Path temp;

  @Test
  public void baseline() throws Exception {
    runChild("baseline", 0, "EXPERIMENT_COMPLETE");
  }

  @Test
  public void mutex() throws Exception {
    runChild("mutex", 0, "EXPERIMENT_COMPLETE");
  }

  @RepeatedTest(10)
  public void freshProcessBaseline() throws Exception {
    runChild("baseline", 0, "EXPERIMENT_COMPLETE baseline iterations=1", true);
  }

  @RepeatedTest(10)
  public void freshProcessMutex() throws Exception {
    runChild("mutex", 0, "EXPERIMENT_COMPLETE mutex iterations=1", true);
  }

  @RepeatedTest(20)
  public void seededFreshBaseline(RepetitionInfo repetition) throws Exception {
    runChild(
        "baseline",
        0,
        "EXPERIMENT_COMPLETE baseline iterations=1",
        true,
        repetition.getCurrentRepetition());
  }

  @RepeatedTest(20)
  public void seededFreshMutex(RepetitionInfo repetition) throws Exception {
    runChild(
        "mutex",
        0,
        "EXPERIMENT_COMPLETE mutex iterations=1",
        true,
        repetition.getCurrentRepetition());
  }

  @Test
  public void cancellation() throws Exception {
    runChild("cancellation", 0, "EXPERIMENT_COMPLETE");
  }

  @Test
  public void traceRaceOnce() throws Exception {
    raceAndReplay();
  }

  @RepeatedTest(5)
  public void raceAndReplay() throws Exception {
    runChild("raceRecord", 0, "EXPERIMENT_RACE_FOUND");
    Path saved = Path.of("build", "coroutine-experiment-evidence");
    Files.createDirectories(saved);
    Files.copy(
        temp.resolve("report-raceRecord/recording/recording.json"),
        saved.resolve("recording.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        temp.resolve("report-raceRecord/recording/random.json"),
        saved.resolve("random.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        temp.resolve("report-raceRecord/recording/strict-state-v1.json"),
        saved.resolve("strict-state-v1.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        temp.resolve("race.properties"),
        saved.resolve("race.properties"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    runChild("raceReplay", 0, "EXPERIMENT_RACE_REPLAYED");
    for (String artifact : List.of("recording.json", "random.json", "strict-state-v1.json")) {
      assertEquals(
          -1L,
          Files.mismatch(
              temp.resolve("report-raceRecord/recording").resolve(artifact),
              saved.resolve(artifact)),
          "replay modified the original " + artifact);
    }
    assertEquals(
        -1L,
        Files.mismatch(temp.resolve("race.properties"), saved.resolve("race.properties")),
        "replay modified the original failure fingerprint");
  }

  @Test
  public void rejectsDetachedSuspended() throws Exception {
    runChild("detachedSuspended", 77, "EXPERIMENT_UNSUPPORTED");
  }

  @Test
  public void rejectsDetachedCompleted() throws Exception {
    runChild("detachedCompleted", 77, "EXPERIMENT_UNSUPPORTED");
  }

  @Test
  public void rejectsDetachedLazy() throws Exception {
    runChild("detachedLazy", 77, "EXPERIMENT_UNSUPPORTED");
  }

  private void runChild(String scenario, int expectedExit, String marker) throws Exception {
    runChild(scenario, expectedExit, marker, false);
  }

  private void runChild(String scenario, int expectedExit, String marker, boolean singleEpoch)
      throws Exception {
    runChild(scenario, expectedExit, marker, singleEpoch, null);
  }

  private void runChild(
      String scenario, int expectedExit, String marker, boolean singleEpoch, Integer seed)
      throws Exception {
    Path log = temp.resolve(scenario + ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
    command.add("-Xmx384m");
    command.add("-XX:ActiveProcessorCount=4");
    if (scenario.equals("mutex") && "1".equals(System.getenv("FRAY_MUTEX_TRACE"))) {
      command.add("-Dfray.coroutine.experiment.mutexTrace=true");
    }
    if (scenario.startsWith("race") && "1".equals(System.getenv("FRAY_TRACE_CLASS_LOADS"))) {
      command.add("-Xlog:class+load=info:stdout:uptime");
    }
    command.add("-Dfray.coroutine.experiment.child=true");
    if (singleEpoch) command.add("-Dfray.coroutine.experiment.single=true");
    if (seed != null) command.add("-Dfray.coroutine.experiment.seed=" + seed);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(CoroutineIsolationExperiment.class.getName());
    command.add(scenario);
    command.add(temp.resolve("report-" + scenario).toString());
    command.add(temp.resolve("report-raceRecord/recording").toString());
    command.add(temp.resolve("race.properties").toString());
    Process child =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    boolean done = child.waitFor(60, TimeUnit.SECONDS);
    if (!done) {
      child.destroyForcibly();
      child.waitFor();
    }
    String output = Files.readString(log);
    System.out.println(
        "CHILD_OUTPUT_BEGIN " + scenario + "\n" + output + "CHILD_OUTPUT_END " + scenario);
    assertTrue(done, () -> "experimental child timed out: " + output);
    assertEquals(expectedExit, child.exitValue(), output);
    assertTrue(output.contains(marker), output);
    if (scenario.startsWith("race") && "1".equals(System.getenv("FRAY_RACE_PRELOAD"))) {
      assertTrue(output.contains("EXPERIMENT_PRELOAD_UNINITIALIZED"), output);
      assertTrue(output.contains("EXPERIMENT_PRELOAD_INITIALIZED_AFTER_SETUP"), output);
    }
    String workloadMarker =
        scenario.startsWith("race")
            ? "EXPERIMENT_WORKLOAD_THROWN "
            : "EXPERIMENT_WORKLOAD_COMPLETE ";
    assertTrue(output.contains(workloadMarker + scenario), output);
    if (expectedExit == 77) {
      assertTrue(output.contains("violations=1"), output);
      assertTrue(!output.contains("EXPERIMENT_SHUTDOWN_BEGIN"), output);
    }
  }

  public static void main(String[] args) throws Exception {
    if (!Boolean.getBoolean("fray.coroutine.experiment.child"))
      throw new IllegalStateException("disposable child required");
    boolean recording = args[0].equals("raceRecord");
    boolean replaying = args[0].equals("raceReplay");
    boolean symmetric =
        (recording || replaying) && "1".equals(System.getenv("FRAY_SYMMETRIC_BOOTSTRAP"));
    if (symmetric && "1".equals(System.getenv("FRAY_RACE_PRELOAD"))) {
      throw new IllegalStateException("run the preload sentinel as a separate control");
    }
    if (symmetric) System.out.println("EXPERIMENT_SYMMETRIC_BOOTSTRAP_PARTIAL");
    System.setProperty("fray.recordSchedule", Boolean.toString(recording || symmetric));
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          uncaught.compareAndSet(null, error);
          error.printStackTrace(System.err);
        });
    AtomicBoolean hostRunning = new AtomicBoolean(true);
    AtomicInteger hostCompletions = new AtomicInteger();
    Thread host =
        new Thread(
            () -> {
              try {
                while (hostRunning.get()) {
                  HostCoroutineProbe.run();
                  hostCompletions.incrementAndGet();
                  Thread.sleep(5);
                }
              } catch (Throwable failure) {
                uncaught.compareAndSet(null, failure);
              }
            },
            "experiment-host-work");
    host.setDaemon(true);
    if (recording || replaying) {
      // Separate the replay probe from the independent concurrent-host acceptance test.
      HostCoroutineProbe.run();
      hostCompletions.incrementAndGet();
    } else {
      host.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (hostCompletions.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
      if (hostCompletions.get() == 0) throw new IllegalStateException("host worker did not start");
    }
    int hostBefore = hostCompletions.get();
    int iterations =
        symmetric || Boolean.getBoolean("fray.coroutine.experiment.single")
            ? 1
            : recording ? 100 : args[0].equals("baseline") || args[0].equals("mutex") ? 10 : 1;
    MutexScheduleRing mutexRing =
        args[0].equals("mutex") && Boolean.getBoolean("fray.coroutine.experiment.mutexTrace")
            ? new MutexScheduleRing()
            : null;
    ExperimentExecutor executor = new ExperimentExecutor(args[0], mutexRing);
    ReplayBootstrap bootstrap = symmetric ? ReplayBootstrap.load(replaying, args) : null;
    StrictRecordedIntRandomness strictRandom =
        symmetric
            ? bootstrap.random
            : replaying
                ? StrictRecordedIntRandomness.fromRecorded(
                    new RecordedRandomProvider(Path.of(args[2], "random.json").toString())
                        .getRandomness())
                : null;
    Long freshSeed = Long.getLong("fray.coroutine.experiment.seed");
    RandomnessProvider randomProvider =
        symmetric
            ? new SymmetricRandomnessProvider(replaying, strictRandom)
            : replaying
                ? () -> strictRandom
                : recording
                    ? new SeededRandomProvider()
                    : freshSeed == null
                        ? new ControlledRandomProvider()
                        : () ->
                            new ControlledRandom(
                                new ArrayList<>(), new ArrayList<>(), new Random(freshSeed));
    if (freshSeed != null) System.out.println("EXPERIMENT_SEED " + freshSeed);
    // Recreate the original RandomScheduler's draws; ReplayScheduler skips those draws and shifts
    // the random stream also used by Fray's runtime. The strict trace verifier still checks
    // choices.
    Scheduler scheduler = new RandomScheduler(randomProvider.getRandomness());
    StrictStateCapture stateCapture = (recording || replaying) ? new StrictStateCapture() : null;
    if (stateCapture != null)
      scheduler = new StrictStateCapturingScheduler(scheduler, stateCapture);
    Configuration config =
        new Configuration(
            new ExecutionInfo(executor, false, false, 10_000),
            Path.of(args[1]),
            iterations,
            30,
            scheduler,
            randomProvider,
            true,
            false,
            true,
            replaying && !symmetric,
            false,
            false,
            NetworkDelegateType.REACTIVE,
            SystemTimeDelegateType.MOCK,
            100_000L,
            !"1".equals(System.getenv("FRAY_RESPECT_TIMED_BLOCKS")),
            false,
            true,
            false,
            false,
            false,
            TimelineCoverageType.NONE);
    StrictReplayVerifier verifier = null;
    StrictStateRecorderV1 stateRecorder = null;
    StrictStateReplayVerifierV1 stateVerifier = null;
    if (symmetric) {
      // Both modes construct both variants and install the same observer wrappers in the same
      // order. This is partial prewarming, not proof that JVM bootstrap state is identical.
      stateRecorder =
          new StrictStateRecorderV1(
              stateCapture,
              new StrictFailureExpectation(
                  "java.lang.IllegalStateException",
                  "org.pastalab.fray.test.poc.CoroutineCases$race$1",
                  "invokeSuspend",
                  "lost update:"),
              true);
      verifier = new StrictReplayVerifier(bootstrap.schedule, bootstrap.failure);
      stateVerifier = new StrictStateReplayVerifierV1(stateCapture, bootstrap.state, true);
      StrictReplayObserverAdapter legacyObserver =
          new StrictReplayObserverAdapter(replaying ? verifier : null);
      if (replaying) verifier.attachRandomnessVia(config, legacyObserver);
      SymmetricObserver stateObserver =
          recording
              ? new SymmetricObserver(stateRecorder, stateRecorder)
              : new SymmetricObserver(stateVerifier, stateVerifier);
      config.getScheduleObservers().add(legacyObserver);
      config.getTestStatusObservers().add(legacyObserver);
      config.getScheduleObservers().add(stateObserver);
      config.getTestStatusObservers().add(stateObserver);
    } else if (recording) {
      stateRecorder =
          new StrictStateRecorderV1(
              stateCapture,
              new StrictFailureExpectation(
                  "java.lang.IllegalStateException",
                  "org.pastalab.fray.test.poc.CoroutineCases$race$1",
                  "invokeSuspend",
                  "lost update:"),
              true);
      config.getScheduleObservers().add(stateRecorder);
      config.getTestStatusObservers().add(stateRecorder);
    }
    if (replaying && !symmetric) {
      Properties saved = new Properties();
      try (var input = Files.newInputStream(Path.of(args[3]))) {
        saved.load(input);
      }
      StackTraceElement frame =
          new StackTraceElement(
              saved.getProperty("class"),
              saved.getProperty("method"),
              saved.getProperty("file"),
              Integer.parseInt(saved.getProperty("line")));
      verifier =
          new StrictReplayVerifier(
              StrictReplayFiles.read(Path.of(args[2], "recording.json")),
              new ReplayFailureFingerprint(
                  saved.getProperty("errorClass"), saved.getProperty("message"), frame));
      config.getScheduleObservers().add(verifier);
      config.getTestStatusObservers().add(verifier);
      verifier.attachRandomness(config);
      stateVerifier =
          new StrictStateReplayVerifierV1(
              stateCapture,
              StrictStateFilesV1.read(Path.of(args[2], "strict-state-v1.json")),
              true);
      config.getScheduleObservers().add(stateVerifier);
      config.getTestStatusObservers().add(stateVerifier);
    }
    if (recording || replaying) {
      TraceDiagnostics diagnostics = new TraceDiagnostics();
      config.getScheduleObservers().add(diagnostics);
      config.getTestStatusObservers().add(diagnostics);
    }
    if (mutexRing != null) {
      config.getScheduleObservers().add(mutexRing);
      config.getTestStatusObservers().add(mutexRing);
    }
    Throwable error = new TestRunner(config).run();
    if (mutexRing != null) mutexRing.dump("runner-returned");
    if (!(recording || replaying)) {
      hostRunning.set(false);
      host.join(TimeUnit.SECONDS.toMillis(10));
      if (host.isAlive()) throw new IllegalStateException("host worker failed to stop");
    }
    if (uncaught.get() != null) throw new AssertionError("uncaught worker failure", uncaught.get());
    if (!(recording || replaying) && hostCompletions.get() <= hostBefore)
      throw new AssertionError("host made no progress during run");
    if (recording || replaying) {
      if (!(error instanceof IllegalStateException)
          || error.getMessage() == null
          || !error.getMessage().contains("lost update")) {
        throw new AssertionError("expected an application lost-update failure", error);
      }
      if (executor.finished < 1
          || (recording && !Files.isRegularFile(Path.of(args[1], "recording", "recording.json")))) {
        throw new AssertionError("race or teardown recording incomplete");
      }
      if (replaying) {
        if (symmetric) {
          // Both verdicts are mandatory; retain both failures for diagnosis if they diverge.
          IllegalStateException mismatch = null;
          try {
            verifier.requireMatched();
          } catch (IllegalStateException e) {
            mismatch = e;
          }
          try {
            stateVerifier.requireMatched();
          } catch (IllegalStateException e) {
            if (mismatch == null) mismatch = e;
            else mismatch.addSuppressed(e);
          }
          if (mismatch != null) throw mismatch;
        } else {
          verifier.requireMatched();
          stateVerifier.requireMatched();
        }
        System.out.println(
            "EXPERIMENT_RACE_REPLAYED " + error + " completed-epochs=" + executor.finished);
      } else {
        stateRecorder.requireRecorded();
        StackTraceElement frame = error.getStackTrace()[0];
        Properties saved = new Properties();
        saved.setProperty("errorClass", error.getClass().getName());
        saved.setProperty("message", error.getMessage());
        saved.setProperty("class", frame.getClassName());
        saved.setProperty("method", frame.getMethodName());
        saved.setProperty("file", frame.getFileName() == null ? "" : frame.getFileName());
        saved.setProperty("line", Integer.toString(frame.getLineNumber()));
        try (var output = Files.newOutputStream(Path.of(args[3]))) {
          saved.store(output, "experimental race fingerprint");
        }
        System.out.println(
            "EXPERIMENT_RACE_FOUND " + error + " completed-epochs=" + executor.finished);
      }
      return;
    }
    if (error != null) throw new AssertionError("Fray reported an error", error);
    if (executor.finished != iterations)
      throw new AssertionError("workload or teardown count: " + executor.finished);
    String key =
        args[0].equals("cancellation")
            ? "fray.experiment.completed.cancellation"
            : "fray.coroutine.completed." + args[0];
    if (!Integer.toString(iterations).equals(System.getProperty(key)))
      throw new AssertionError("workload completion count: " + System.getProperty(key));
    System.out.println(
        "EXPERIMENT_COMPLETE "
            + args[0]
            + " iterations="
            + iterations
            + " host-progress="
            + (hostCompletions.get() - hostBefore));
  }

  private static class SeededRandomProvider implements RandomnessProvider {
    private int iteration;

    public Randomness getRandomness() {
      return new ControlledRandom(
          new ArrayList<>(), new ArrayList<>(), new Random(74231L + iteration++));
    }
  }

  /** Same provider class in both modes; the underlying RNG implementations still differ. */
  private static class SymmetricRandomnessProvider implements RandomnessProvider {
    private final boolean replaying;
    private final StrictRecordedIntRandomness strict;
    private final SeededRandomProvider seeded = new SeededRandomProvider();

    SymmetricRandomnessProvider(boolean replaying, StrictRecordedIntRandomness strict) {
      this.replaying = replaying;
      this.strict = strict;
    }

    public Randomness getRandomness() {
      return replaying ? strict : seeded.getRandomness();
    }
  }

  /** A common observer type/order; the recorder and verifier remain distinct implementations. */
  private static class SymmetricObserver
      implements ScheduleObserver<ThreadContext>, TestStatusObserver {
    private final ScheduleObserver<ThreadContext> schedule;
    private final TestStatusObserver status;

    SymmetricObserver(ScheduleObserver<ThreadContext> schedule, TestStatusObserver status) {
      this.schedule = schedule;
      this.status = status;
    }

    public void onExecutionStart() throws java.rmi.RemoteException {
      if (status != null) status.onExecutionStart();
    }

    public void onReportError(Throwable error) throws java.rmi.RemoteException {
      if (status != null) status.onReportError(error);
    }

    public void onExecutionDone(Throwable error) throws java.rmi.RemoteException {
      if (status != null) status.onExecutionDone(error);
    }

    public void saveToReportFolder(Path path) throws java.rmi.RemoteException {
      if (status != null) status.saveToReportFolder(path);
    }

    public void onContextSwitch(ThreadContext current, ThreadContext next)
        throws java.rmi.RemoteException {
      if (schedule != null) schedule.onContextSwitch(current, next);
    }

    public void onNewSchedule(Collection<? extends ThreadContext> all, ThreadContext selected)
        throws java.rmi.RemoteException {
      if (schedule != null) schedule.onNewSchedule(all, selected);
    }
  }

  /** Load valid dummy or actual artifacts through identical parser APIs before Runtime.start(). */
  private static class ReplayBootstrap {
    final StrictRecordedIntRandomness random;
    final List<ScheduleRecording> schedule;
    final StrictStateTraceV1 state;
    final ReplayFailureFingerprint failure;

    ReplayBootstrap(
        StrictRecordedIntRandomness random,
        List<ScheduleRecording> schedule,
        StrictStateTraceV1 state,
        ReplayFailureFingerprint failure) {
      this.random = random;
      this.schedule = schedule;
      this.state = state;
      this.failure = failure;
    }

    static ReplayBootstrap load(boolean replaying, String[] args) throws Exception {
      Path directory = Path.of(args[1] + "-symmetric-bootstrap");
      Files.createDirectories(directory);
      Path randomFile = directory.resolve("random.json");
      Path scheduleFile = directory.resolve("recording.json");
      Path stateFile = directory.resolve("strict-state-v1.json");
      Path fingerprintFile = directory.resolve("race.properties");
      Files.writeString(
          randomFile,
          "{\"type\":\"org.pastalab.fray.core.randomness.ControlledRandom\",\"integers\":[1]}");
      Files.writeString(
          scheduleFile, "[{\"scheduled\":0,\"enabled\":[0],\"operation\":\"example.Read\"}]");
      Files.writeString(
          stateFile,
          "{\"version\":1,\"failure\":{\"exceptionClass\":\"java.lang.IllegalStateException\","
              + "\"message\":\"lost update: dummy\",\"topClass\":\"example.Dummy\",\"topMethod\":\"run\","
              + "\"topFile\":\"Dummy.java\",\"topLine\":1},\"steps\":[{\"selectedIndex\":0,"
              + "\"runnableIndices\":[0],\"threads\":[{\"index\":0,\"state\":\"Runnable\","
              + "\"pendingOperation\":\"example.Read\",\"ownedCoroutineWorker\":true,\"workerRngState\":1}]}]}");
      Files.writeString(
          fingerprintFile,
          "errorClass=java.lang.IllegalStateException\nmessage=lost update: dummy\nclass=example.Dummy\n"
              + "method=run\nfile=Dummy.java\nline=1\n");
      if (replaying) {
        randomFile = Path.of(args[2], "random.json");
        scheduleFile = Path.of(args[2], "recording.json");
        stateFile = Path.of(args[2], "strict-state-v1.json");
        fingerprintFile = Path.of(args[3]);
      }
      Properties saved = new Properties();
      try (var input = Files.newInputStream(fingerprintFile)) {
        saved.load(input);
      }
      StackTraceElement frame =
          new StackTraceElement(
              saved.getProperty("class"),
              saved.getProperty("method"),
              saved.getProperty("file"),
              Integer.parseInt(saved.getProperty("line")));
      ReplayFailureFingerprint failure =
          new ReplayFailureFingerprint(
              saved.getProperty("errorClass"), saved.getProperty("message"), frame);
      StrictRecordedIntRandomness random =
          StrictRecordedIntRandomness.fromRecorded(
              new RecordedRandomProvider(randomFile.toString()).getRandomness());
      return new ReplayBootstrap(
          random,
          StrictReplayFiles.read(scheduleFile),
          StrictStateFilesV1.read(stateFile),
          failure);
    }
  }

  /** Diagnostic window; not part of the supported replay protocol. */
  private static class TraceDiagnostics
      implements ScheduleObserver<ThreadContext>, TestStatusObserver {
    int index;

    public void onExecutionStart() {
      index = 0;
    }

    public void onReportError(Throwable error) {}

    public void onExecutionDone(Throwable error) {}

    public void saveToReportFolder(Path path) {}

    public void onContextSwitch(ThreadContext current, ThreadContext next) {}

    public void onNewSchedule(Collection<? extends ThreadContext> all, ThreadContext selected) {
      if (index <= 220) {
        System.out.println(
            "TRACE_DIAGNOSTIC "
                + index
                + " selected="
                + selected.getIndex()
                + " op="
                + selected.getPendingOperation().getClass().getSimpleName()
                + " runnable="
                + all.stream()
                    .filter(ThreadContext::schedulable)
                    .map(ThreadContext::getIndex)
                    .sorted()
                    .toList()
                + " stack="
                + Arrays.stream(Thread.currentThread().getStackTrace())
                    .filter(
                        frame ->
                            !frame.getClassName().startsWith("org.pastalab.fray.core.")
                                && !frame
                                    .getClassName()
                                    .startsWith(
                                        "org.pastalab.fray.poc.CoroutineIsolationExperiment$TraceDiagnostics")
                                && !frame.getClassName().equals("org.pastalab.fray.runtime.Runtime")
                                && !frame.getClassName().equals("java.lang.Thread"))
                    .limit(6)
                    .toList());
      }
      index++;
    }
  }

  /**
   * Opt-in bounded observations; schedulableApprox is NOT the scheduler's actual candidate list.
   */
  private static class MutexScheduleRing
      implements ScheduleObserver<ThreadContext>, TestStatusObserver {
    private static final int CAPACITY = 100;
    private static final int MAX_THREADS = 64;
    private final Slot[] slots = new Slot[CAPACITY];
    private int next;
    private int size;
    private long sequence;
    private volatile ClassLoader traceLoader;

    private static class Slot {
      long sequence;
      int selected;
      String selectedOperation;
      int count;
      boolean truncated;
      String taskSnapshot;
      final int[] index = new int[MAX_THREADS];
      final String[] state = new String[MAX_THREADS];
      final String[] operation = new String[MAX_THREADS];
      final boolean[] schedulable = new boolean[MAX_THREADS];
    }

    MutexScheduleRing() {
      for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    public void onExecutionStart() {
      org.pastalab.fray.runtime.Runtime.onSkipPrimitive("mutex schedule ring");
      try {
        org.pastalab.fray.core.observers.ExperimentalParkTrace.reset();
        synchronized (this) {
          next = 0;
          size = 0;
          sequence = 0;
          traceLoader = null;
        }
      } finally {
        org.pastalab.fray.runtime.Runtime.onSkipPrimitiveDone("mutex schedule ring");
      }
    }

    public void onReportError(Throwable error) {}

    public void onExecutionDone(Throwable error) {}

    public void saveToReportFolder(Path path) {}

    public void onContextSwitch(ThreadContext current, ThreadContext next) {}

    public void onNewSchedule(Collection<? extends ThreadContext> all, ThreadContext selected) {
      org.pastalab.fray.runtime.Runtime.onSkipPrimitive("mutex schedule ring");
      try {
        synchronized (this) {
          Slot slot = slots[next];
          slot.sequence = sequence++;
          slot.taskSnapshot = null;
          // Ordinal is observer-local and zero-based; it is not asserted to equal RunContext.step.
          if (slot.sequence >= 9899) {
            try {
              slot.taskSnapshot = CoroutineTaskTracker.diagnosticSnapshot(traceLoader);
            } catch (Throwable diagnosticError) {
              slot.taskSnapshot = "diagnostic-error=" + diagnosticError.getClass().getName();
            }
          }
          slot.selected = selected.getIndex();
          slot.selectedOperation = selected.getPendingOperation().getClass().getName();
          slot.count = 0;
          slot.truncated = false;
          for (ThreadContext context : all) {
            if (slot.count == MAX_THREADS) {
              slot.truncated = true;
              break;
            }
            int i = slot.count++;
            slot.index[i] = context.getIndex();
            slot.state[i] = context.getState().name();
            slot.operation[i] = context.getPendingOperation().getClass().getName();
            slot.schedulable[i] = context.schedulable();
          }
          next = (next + 1) % CAPACITY;
          if (size < CAPACITY) size++;
        }
      } finally {
        org.pastalab.fray.runtime.Runtime.onSkipPrimitiveDone("mutex schedule ring");
      }
    }

    void dump(String reason) {
      org.pastalab.fray.runtime.Runtime.onSkipPrimitive("mutex schedule ring dump");
      try {
        List<String> snapshot = new ArrayList<>();
        synchronized (this) {
          int start = (next - size + CAPACITY) % CAPACITY;
          for (int n = 0; n < size; n++) {
            Slot slot = slots[(start + n) % CAPACITY];
            StringBuilder line = new StringBuilder();
            line.append("MUTEX_SCHEDULE ")
                .append(slot.sequence)
                .append(" chosen=")
                .append(slot.selected)
                .append(':')
                .append(slot.selectedOperation)
                .append(" schedulableApprox=[");
            boolean first = true;
            for (int i = 0; i < slot.count; i++)
              if (slot.schedulable[i]) {
                if (!first) line.append(',');
                line.append(slot.index[i]);
                first = false;
              }
            line.append("] states=[");
            for (int i = 0; i < slot.count; i++) {
              if (i != 0) line.append(',');
              line.append(slot.index[i])
                  .append(':')
                  .append(slot.state[i])
                  .append(':')
                  .append(slot.operation[i]);
            }
            if (slot.truncated) line.append(",TRUNCATED");
            line.append(']');
            if (slot.taskSnapshot != null)
              line.append(" trackerSnapshot={").append(slot.taskSnapshot).append('}');
            snapshot.add(line.toString());
          }
        }
        System.err.println(
            "MUTEX_SCHEDULE_BEGIN " + reason + " (schedulableApprox is not actual candidates)");
        snapshot.forEach(System.err::println);
        System.err.println("MUTEX_SCHEDULE_END " + reason);
        System.err.flush();
        org.pastalab.fray.core.observers.ExperimentalParkTrace.dump(reason);
      } finally {
        org.pastalab.fray.runtime.Runtime.onSkipPrimitiveDone("mutex schedule ring dump");
      }
    }
  }

  private static class ExperimentExecutor implements Executor {
    private static final String EXPECTED_SHA256 =
        "069c5988633230e074ec0d39321ec3cdaa4547c49e90ba936c63d8fc91c8c00d";
    private final String scenario;
    private final MutexScheduleRing mutexRing;
    volatile int finished;
    private final Set<Long> seenWorkers = new HashSet<>();
    private final Set<Integer> seenLoaders = new HashSet<>();

    ExperimentExecutor(String scenario, MutexScheduleRing mutexRing) {
      this.scenario = scenario;
      this.mutexRing = mutexRing;
    }

    public void beforeExecution() {}

    public void afterExecution() {}

    public void execute(boolean resetClassloader) {
      if (!resetClassloader)
        throw new IllegalStateException("experiment requires a fresh classloader");
      ClassLoader original = Thread.currentThread().getContextClassLoader();
      FrayClassLoader loader = null;
      try {
        java.lang.reflect.Method targetMethod;
        java.lang.reflect.Method preloadSentinel = null;
        org.pastalab.fray.runtime.Runtime.onSkipPrimitive("experiment setup");
        try {
          URL[] urls =
              Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                  .map(
                      path -> {
                        try {
                          return Path.of(path).toUri().toURL();
                        } catch (Exception e) {
                          throw new IllegalArgumentException(e);
                        }
                      })
                  .toArray(URL[]::new);
          loader = new FrayClassLoader(urls, original, true);
          if (!seenLoaders.add(System.identityHashCode(loader)))
            throw new IllegalStateException("reused loader identity");
          var matches = loader.findResources("kotlinx/coroutines/JobSupport.class");
          if (!matches.hasMoreElements())
            throw new IllegalStateException("coroutine runtime missing");
          URL resource = matches.nextElement();
          if (matches.hasMoreElements())
            throw new IllegalStateException("multiple coroutine runtimes");
          if (!(resource.openConnection() instanceof JarURLConnection))
            throw new IllegalStateException("coroutine runtime must be a jar");
          URL jar = ((JarURLConnection) resource.openConnection()).getJarFileURL();
          byte[] bytes = Files.readAllBytes(Path.of(jar.toURI()));
          String digest =
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
          if (!EXPECTED_SHA256.equals(digest))
            throw new IllegalStateException("unsupported coroutine runtime digest");
          Thread.currentThread().setContextClassLoader(loader);
          Class<?> root = Class.forName("kotlinx.coroutines.BlockingCoroutine", false, loader);
          CoroutineEpochGate.begin(loader);
          CoroutineJobTracker.begin(loader, root, Thread.currentThread());
          CoroutineTaskTracker.begin(loader);
          if (mutexRing != null) mutexRing.traceLoader = loader;
          if (scenario.startsWith("race") && "1".equals(System.getenv("FRAY_RACE_PRELOAD"))) {
            // Diagnostic only: these are the known application classes on the current race path,
            // not an authenticated/admitted dependency graph or a general class-loading policy.
            System.clearProperty("fray.experiment.racePreload.initialized");
            System.clearProperty("fray.experiment.racePreload.setupEnded");
            for (String name :
                List.of(
                    "org.pastalab.fray.test.poc.CoroutineCases",
                    "org.pastalab.fray.test.poc.CoroutineCases$race$1",
                    "org.pastalab.fray.test.poc.CoroutineCases$race$1$jobs$1$1",
                    "org.pastalab.fray.test.poc.IsolatedCoroutineWorkloads",
                    "org.pastalab.fray.test.poc.RacePreloadSentinel")) {
              var sources = loader.findResources(name.replace('.', '/') + ".class");
              if (!sources.hasMoreElements())
                throw new IllegalStateException("preload class missing: " + name);
              sources.nextElement();
              if (sources.hasMoreElements())
                throw new IllegalStateException("duplicate preload class: " + name);
              Class<?> defined = Class.forName(name, false, loader);
              if (defined.getClassLoader() != loader)
                throw new IllegalStateException("preload parent fallback: " + name);
              if (name.endsWith(".RacePreloadSentinel"))
                preloadSentinel = defined.getMethod("touch");
            }
            if (System.getProperty("fray.experiment.racePreload.initialized") != null
                || preloadSentinel == null) {
              throw new IllegalStateException(
                  "preload unexpectedly initialized the diagnostic class");
            }
            System.out.println("EXPERIMENT_PRELOAD_UNINITIALIZED");
          }
          Class<?> target =
              Class.forName("org.pastalab.fray.test.poc.IsolatedCoroutineWorkloads", false, loader);
          targetMethod = target.getMethod(scenario.startsWith("race") ? "race" : scenario);
        } finally {
          org.pastalab.fray.runtime.Runtime.onSkipPrimitiveDone("experiment setup");
        }
        if (preloadSentinel != null) {
          System.setProperty("fray.experiment.racePreload.setupEnded", "true");
          preloadSentinel.invoke(null);
          if (!"true".equals(System.getProperty("fray.experiment.racePreload.initialized"))) {
            throw new IllegalStateException("diagnostic initializer did not run after setup");
          }
          System.out.println("EXPERIMENT_PRELOAD_INITIALIZED_AFTER_SETUP");
        }
        Throwable applicationFailure = null;
        try {
          targetMethod.invoke(null);
        } catch (InvocationTargetException e) {
          applicationFailure = e.getTargetException();
        }
        if (applicationFailure == null) {
          System.out.println("EXPERIMENT_WORKLOAD_COMPLETE " + scenario);
        } else {
          // This is a diagnostic in a disposable child, not an application success marker.
          org.pastalab.fray.runtime.Runtime.onSkipPrimitive(
              "experiment target failure diagnostics");
          try {
            System.err.println("EXPERIMENT_WORKLOAD_THROWN " + scenario);
            applicationFailure.printStackTrace(System.err);
            if (mutexRing != null) {
              try {
                System.err.println(
                    "EXPERIMENT_TASK_SNAPSHOT " + CoroutineTaskTracker.diagnosticSnapshot(loader));
              } catch (Throwable ignored) {
                // Optional diagnostics must not change the target failure or epoch state.
              }
            }
            System.err.flush();
          } finally {
            org.pastalab.fray.runtime.Runtime.onSkipPrimitiveDone(
                "experiment target failure diagnostics");
          }
          if (mutexRing != null) mutexRing.dump("target-threw");
        }
        try {
          // Give already-owned tasks a bounded opportunity to leave runSafely before sealing.
          for (int i = 0; i < 16; i++) Thread.yield();
          System.out.println(
              "EXPERIMENT_BOUNDARY " + CoroutineEpochGate.atomicSeal(loader, applicationFailure));
        } catch (IllegalStateException unsafe) {
          // This JVM is disposable. Do not call Dispatchers.shutdown with live/unknown jobs.
          System.out.println("EXPERIMENT_UNSUPPORTED " + unsafe.getMessage());
          if (mutexRing != null) mutexRing.dump("boundary-rejected");
          System.out.flush();
          java.lang.Runtime.getRuntime().halt(77);
        }
        Class<?> dispatchers = Class.forName("kotlinx.coroutines.Dispatchers", true, loader);
        if (dispatchers.getClassLoader() != loader)
          throw new IllegalStateException("host dispatcher ownership");
        org.pastalab.fray.runtime.Runtime.onSkipPrimitive("experiment worker identity");
        try {
          int workers = 0;
          for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getClass().getClassLoader() == loader) {
              if (!seenWorkers.add(thread.getId()))
                throw new IllegalStateException("reused worker identity");
              workers++;
            }
          }
          if (workers == 0) throw new IllegalStateException("no owned dispatcher worker observed");
        } finally {
          org.pastalab.fray.runtime.Runtime.onSkipPrimitiveDone("experiment worker identity");
        }
        // Pinned coroutines 1.10.1 creates DefaultExecutor's worker as a plain java.lang.Thread,
        // so its thread class's loader does not reveal ownership. This experiment does not support
        // DefaultExecutor work: require its exact singleton to still be FRESH with no thread.
        Class<?> defaultExecutor =
            Class.forName("kotlinx.coroutines.DefaultExecutor", true, loader);
        if (defaultExecutor.getClassLoader() != loader)
          throw new IllegalStateException("foreign DefaultExecutor");
        var defaultThread = defaultExecutor.getDeclaredField("_thread");
        var defaultStatus = defaultExecutor.getDeclaredField("debugStatus");
        if (defaultThread.getType() != Thread.class
            || defaultStatus.getType() != int.class
            || !java.lang.reflect.Modifier.isStatic(defaultThread.getModifiers())
            || !java.lang.reflect.Modifier.isStatic(defaultStatus.getModifiers())
            || !defaultThread.trySetAccessible()
            || !defaultStatus.trySetAccessible()) {
          throw new IllegalStateException("unsupported DefaultExecutor layout");
        }
        if (defaultThread.get(null) != null || defaultStatus.getInt(null) != 0) {
          throw new IllegalStateException(
              "DefaultExecutor was started; experimental mode rejects it");
        }
        System.out.println("EXPERIMENT_SHUTDOWN_BEGIN");
        dispatchers.getMethod("shutdown").invoke(dispatchers.getField("INSTANCE").get(null));
        System.out.println("EXPERIMENT_SHUTDOWN_COMPLETE");
        if (defaultThread.get(null) != null || defaultStatus.getInt(null) != 4) {
          throw new IllegalStateException(
              "DefaultExecutor did not remain threadless through shutdown");
        }
        org.pastalab.fray.runtime.Runtime.onSkipPrimitive("experiment worker check");
        try {
          for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != Thread.currentThread()
                && thread.isAlive()
                && (thread.getClass().getClassLoader() == loader
                    || thread.getContextClassLoader() == loader)) {
              throw new IllegalStateException("owned worker or loader-context thread still alive");
            }
          }
        } finally {
          org.pastalab.fray.runtime.Runtime.onSkipPrimitiveDone("experiment worker check");
        }
        System.out.println("EXPERIMENT_SEALED " + CoroutineEpochGate.verifySealed(loader));
        CoroutineEpochGate.endAfterTermination(loader);
        loader.close();
        if (CoroutineInstrumentationOwners.owns(loader))
          throw new IllegalStateException("owner registration survived teardown");
        finished++;
        if (applicationFailure instanceof RuntimeException)
          throw (RuntimeException) applicationFailure;
        if (applicationFailure instanceof Error) throw (Error) applicationFailure;
        if (applicationFailure != null)
          throw new IllegalStateException("checked application failure", applicationFailure);
      } catch (InvocationTargetException e) {
        throw new IllegalStateException("target or teardown failed", e.getTargetException());
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException("experiment failed", e);
      } finally {
        Thread.currentThread().setContextClassLoader(original);
      }
    }
  }
}
