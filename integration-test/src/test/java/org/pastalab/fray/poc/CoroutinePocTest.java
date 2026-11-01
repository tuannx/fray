package org.pastalab.fray.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pastalab.fray.core.FrayInternalError;
import org.pastalab.fray.core.TestRunner;
import org.pastalab.fray.core.command.Configuration;
import org.pastalab.fray.core.command.ExecutionInfo;
import org.pastalab.fray.core.command.MethodExecutor;
import org.pastalab.fray.core.command.NetworkDelegateType;
import org.pastalab.fray.core.command.SystemTimeDelegateType;
import org.pastalab.fray.core.observers.TimelineCoverageType;
import org.pastalab.fray.core.randomness.ControlledRandomProvider;
import org.pastalab.fray.core.scheduler.RandomScheduler;

/** Experimental, bounded JVM coroutine compatibility probes, not a claim of full support. */
@EnabledIfSystemProperty(named = "fray.coroutine.poc", matches = "true")
public class CoroutinePocTest {

  private Throwable run(String method, int iterations, boolean interleaveMemoryOps) {
    System.clearProperty("fray.coroutine.completed." + method);
    System.setProperty("fray.recordSchedule", "true");
    List<String> classpaths =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).toList();
    Configuration config =
        new Configuration(
            new ExecutionInfo(
                new MethodExecutor(
                    "org.pastalab.fray.test.poc.CoroutineCases",
                    method,
                    List.of(),
                    classpaths,
                    new HashMap<>()),
                false,
                interleaveMemoryOps,
                -1),
            Path.of("build", "coroutine-poc", method),
            iterations,
            30,
            new RandomScheduler(),
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
    return new TestRunner(config).run();
  }

  private boolean interleaveMemoryOps() {
    return Boolean.getBoolean("fray.coroutine.memory");
  }

  @Test
  public void baseline() {
    assertNull(run("baseline", 10, interleaveMemoryOps()));
    assertEquals("10", System.getProperty("fray.coroutine.completed.baseline"));
  }

  @Test
  public void mutex() {
    assertNull(run("mutex", 10, interleaveMemoryOps()));
    assertEquals("10", System.getProperty("fray.coroutine.completed.mutex"));
  }

  @Test
  public void race() {
    Throwable result = run("race", 100, interleaveMemoryOps());
    assertNotNull(result, "no application race observed in the bounded search");
    assertFalse(
        result instanceof FrayInternalError, "Fray internal error, not an application race");
    assertTrue(result.toString().contains("lost update"), () -> "unexpected failure: " + result);
  }
}
