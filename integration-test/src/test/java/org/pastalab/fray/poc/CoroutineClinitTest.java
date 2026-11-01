package org.pastalab.fray.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kotlinx.coroutines.frayfixture.ClinitState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pastalab.fray.core.TestRunner;
import org.pastalab.fray.core.ThreadContext;
import org.pastalab.fray.core.command.Configuration;
import org.pastalab.fray.core.command.ExecutionInfo;
import org.pastalab.fray.core.command.MethodExecutor;
import org.pastalab.fray.core.command.NetworkDelegateType;
import org.pastalab.fray.core.command.SystemTimeDelegateType;
import org.pastalab.fray.core.observers.TimelineCoverageType;
import org.pastalab.fray.core.randomness.ControlledRandom;
import org.pastalab.fray.core.randomness.ControlledRandomProvider;
import org.pastalab.fray.core.randomness.Randomness;
import org.pastalab.fray.core.scheduler.Scheduler;

/** A child process bounds the deliberately deadlocking pre-fix schedule. */
public class CoroutineClinitTest {
  @TempDir Path temp;

  @Test
  public void initializerDoesNotYieldItsJvmInitializationLock() throws Exception {
    Path log = temp.resolve("child.log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(CoroutineClinitTest.class.getName());
    command.add(temp.resolve("report").toString());
    Process p =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    boolean done = p.waitFor(20, TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      p.waitFor();
    }
    String output = Files.readString(log);
    assertTrue(done, () -> "child timed out waiting for class initialization: " + output);
    assertEquals(0, p.exitValue(), output);
    assertTrue(output.contains("CLINIT_COMPLETED"), output);
  }

  public static void main(String[] args) {
    List<String> cp =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).toList();
    Configuration config =
        new Configuration(
            new ExecutionInfo(
                new MethodExecutor(
                    "org.pastalab.fray.test.clinit.ClinitCase",
                    "run",
                    List.of(),
                    cp,
                    new HashMap<>()),
                false,
                false,
                -1),
            Path.of(args[0]),
            1,
            30,
            new TriggerScheduler(new ControlledRandom()),
            new ControlledRandomProvider(),
            true,
            false,
            true,
            false,
            false,
            false,
            NetworkDelegateType.REACTIVE,
            SystemTimeDelegateType.NONE,
            100_000L,
            true,
            false,
            true,
            false,
            false,
            false,
            TimelineCoverageType.RESOURCE_ORDERING);
    Throwable error = new TestRunner(config).run();
    if (error != null) throw new AssertionError("unexpected Fray result", error);
    if (!ClinitState.completed) throw new AssertionError("application did not finish");
    System.out.println("CLINIT_COMPLETED");
  }

  static class TriggerScheduler extends Scheduler {
    TriggerScheduler(Randomness randomness) {
      super(randomness);
    }

    public ThreadContext scheduleNextOperation(
        List<ThreadContext> threads, Collection<ThreadContext> allThreads) {
      Thread current = Thread.currentThread();
      boolean atUpdater =
          current.getName().equals("clinit-owner")
              && Arrays.stream(current.getStackTrace())
                  .anyMatch(
                      e ->
                          e.getClassName()
                                  .equals("java.util.concurrent.atomic.AtomicIntegerFieldUpdater")
                              && e.getMethodName().equals("newUpdater"));
      if (atUpdater) {
        for (ThreadContext t : threads)
          if (t.getThread().getName().equals("clinit-waiter")) return t;
      }
      for (ThreadContext t : threads) if (t.getThread() == current) return t;
      return threads.get(0);
    }

    public Scheduler nextIteration(Randomness randomness) {
      return new TriggerScheduler(randomness);
    }
  }
}
