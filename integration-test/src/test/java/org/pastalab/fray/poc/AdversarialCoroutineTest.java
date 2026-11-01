package org.pastalab.fray.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Negative controls for cross-loader work which must not be accepted as quiescent. */
@EnabledIfSystemProperty(named = "fray.coroutine.experiment", matches = "true")
public class AdversarialCoroutineTest {
  @TempDir Path temp;

  /** This class is parent-loaded because org.pastalab.fray.poc is excluded from Fray loading. */
  public static final class HostBridge {
    private static volatile Runnable pending;
    private static volatile Object retained;

    public static void queue(Runnable task) {
      pending = task;
    }

    public static void retain(Object value) {
      retained = value;
    }

    public static void release() {
      Runnable task = pending;
      pending = null;
      task.run();
    }

    public static Object retained() {
      return retained;
    }
  }

  /** Defines only the requested subclass itself; all coroutine classes resolve from its parent. */
  public static final class DescendantLoader extends ClassLoader {
    public DescendantLoader(ClassLoader parent) {
      super(parent);
    }

    public Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  @Test
  public void rejectsChildLoadedUnfinishedJob() throws Exception {
    Result result = execute("descendant");
    assertEquals(77, result.exit, result.output);
    assertTrue(result.output.contains("EXPERIMENT_UNSUPPORTED"), result.output);
    assertFalse(result.output.contains("DESCENDANT_UNFINISHED_AFTER_TEARDOWN"), result.output);
  }

  @Test
  public void rejectsHostCallbackEscapingTheEpoch() throws Exception {
    Result result = execute("callback");
    assertEquals(77, result.exit, result.output);
    assertTrue(result.output.contains("EXPERIMENT_UNSUPPORTED"), result.output);
    assertFalse(result.output.contains("ESCAPED_CALLBACK_AFTER_TEARDOWN"), result.output);
  }

  private record Result(int exit, String output) {}

  private Result execute(String scenario) throws Exception {
    Path classes = temp.resolve("classes-" + scenario);
    Files.createDirectories(classes);
    Path sources = temp.resolve("sources-" + scenario);
    Files.createDirectories(sources);
    Path workload = sources.resolve("IsolatedCoroutineWorkloads.java");
    String action =
        scenario.equals("callback")
            ? "AdversarialCoroutineTest.HostBridge.queue(() -> System.setProperty(\"adversarial.callback\", \"ran\"));"
            : "byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty(\"fray.adversarial.classes\"), \"adversarial\", \"DescendantJob.class\"));"
                + "Class<?> c = new AdversarialCoroutineTest.DescendantLoader(Thread.currentThread().getContextClassLoader()).define(\"adversarial.DescendantJob\", b);"
                + "Object j = c.getConstructor().newInstance();"
                + "if (((Boolean)c.getMethod(\"isCompleted\").invoke(j))) throw new AssertionError(\"job was already complete\");"
                + "AdversarialCoroutineTest.HostBridge.retain(j);";
    Files.writeString(
        workload,
        "package org.pastalab.fray.test.poc;\n"
            + "import org.pastalab.fray.poc.AdversarialCoroutineTest;\n"
            + "public final class IsolatedCoroutineWorkloads { public static void baseline() throws Exception { "
            + action
            + " CoroutineCases.baseline(); } }\n");
    Path subclass = sources.resolve("DescendantJob.java");
    Files.writeString(
        subclass,
        "package adversarial; public final class DescendantJob extends kotlinx.coroutines.JobSupport {"
            + " public DescendantJob() { super(true); } }\n");
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "a JDK with JavaCompiler is required");
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    int compile =
        compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classes.toString(),
            workload.toString(),
            subclass.toString());
    assertEquals(0, compile, diagnostics.toString(StandardCharsets.UTF_8));

    Path log = temp.resolve(scenario + ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
    command.add("-Xmx384m");
    command.add("-XX:ActiveProcessorCount=4");
    command.add("-Dfray.coroutine.experiment.child=true");
    command.add("-Dfray.adversarial.classes=" + classes);
    command.add("-cp");
    command.add(classes + File.pathSeparator + System.getProperty("java.class.path"));
    command.add(AdversarialCoroutineTest.class.getName());
    command.add(scenario);
    command.add(temp.resolve("report-" + scenario).toString());
    Process child =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    boolean done = child.waitFor(60, TimeUnit.SECONDS);
    if (!done) {
      child.destroyForcibly();
      child.waitFor();
    }
    String output = Files.readString(log);
    System.out.println(
        "ADVERSARIAL_OUTPUT_BEGIN "
            + scenario
            + "\n"
            + output
            + "ADVERSARIAL_OUTPUT_END "
            + scenario);
    assertTrue(done, () -> "adversarial child timed out: " + output);
    return new Result(child.exitValue(), output);
  }

  /** Child-only entry point. A correct implementation rejects before either final marker. */
  public static void main(String[] args) throws Exception {
    CoroutineIsolationExperiment.main(new String[] {"baseline", args[1]});
    if (args[0].equals("callback")) {
      HostBridge.release();
      if ("ran".equals(System.getProperty("adversarial.callback"))) {
        System.out.println("ESCAPED_CALLBACK_AFTER_TEARDOWN");
      }
    } else {
      Object job = HostBridge.retained();
      if (job != null && !(Boolean) job.getClass().getMethod("isCompleted").invoke(job)) {
        System.out.println("DESCENDANT_UNFINISHED_AFTER_TEARDOWN");
      }
    }
  }
}
