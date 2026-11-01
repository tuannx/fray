package org.pastalab.fray.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.pastalab.fray.core.command.FrayClassLoader;
import org.pastalab.fray.instrumentation.base.CooperativeClosedGraph;
import org.pastalab.fray.instrumentation.base.PinnedCooperativeClass;
import org.pastalab.fray.instrumentation.base.VerifiedCooperativeGraph;
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners;
import org.pastalab.fray.runtime.Delegate;
import org.pastalab.fray.runtime.Runtime;

/** Opt-in real-agent handshake experiment; synthetic only, not coroutine-support evidence. */
@EnabledIfSystemProperty(named = "fray.coroutine.experiment", matches = "true")
public class StrictLoaderAgentExperiment {
  private static final String ROOT = "example/app/StrictAgentFixture";
  private static final String DOT_ROOT = ROOT.replace('/', '.');

  @Test
  public void actualAgentRunsOwnedFieldHooks() throws Exception {
    runChild("positive", "STRICT_POSITIVE_HOOKS reads=1 writes=1");
  }

  @Test
  public void absentAgentRejectsUntransformedClass() throws Exception {
    runChild("missing-agent", "STRICT_EXPECTED_MISSING_AGENT");
  }

  @Test
  public void excludedEntryRejectsBeforeInvocation() throws Exception {
    runChild("excluded", "STRICT_EXPECTED_EXCLUSION");
  }

  @Test
  public void untrustedParentClassRejectsAndPoisons() throws Exception {
    runChild("untrusted", "STRICT_EXPECTED_UNTRUSTED");
  }

  @Test
  public void boundaryBeforeEntryRejectsAndPoisons() throws Exception {
    runChild("boundary", "STRICT_EXPECTED_EARLY_BOUNDARY");
  }

  @Test
  public void wrongFixtureDigestIsRejected() throws Exception {
    runChild("wrong-digest", "STRICT_EXPECTED_WRONG_DIGEST");
  }

  private void runChild(String mode, String expected) throws Exception {
    List<String> input = ManagementFactory.getRuntimeMXBean().getInputArguments();
    List<String> agents = input.stream().filter(arg -> arg.startsWith("-javaagent:")).toList();
    assertEquals(1, agents.size(), "the parent must have exactly one explicit Fray javaagent");
    assertTrue(agents.get(0).contains("fray-instrumentation-agent"), agents.toString());
    List<String> nativeAgents =
        input.stream().filter(arg -> arg.startsWith("-agentpath:")).toList();
    assertEquals(
        1, nativeAgents.size(), "the parent must have exactly one explicit Fray JVMTI agent");
    assertTrue(nativeAgents.get(0).contains("libjvmti"), nativeAgents.toString());

    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-ea");
    command.add("-Xmx256m");
    command.add("-XX:ActiveProcessorCount=4");
    command.add("-XX:+DisableAttachMechanism");
    command.add(nativeAgents.get(0));
    if (!mode.equals("missing-agent")) command.add(agents.get(0));
    // The fixture generator itself uses unshaded ASM; do not instrument that framework library.
    command.add(
        "-Dfray.transformation.excludePrefixes=org.objectweb.asm"
            + (mode.equals("excluded") ? ",example.app" : ""));
    command.add("-Dfray.debug=false");
    command.add("-Dfray.strictLoaderExperiment.child=true");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(StrictLoaderAgentExperiment.class.getName());
    command.add(mode);

    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    // Do not accidentally inject an agent or options into the supposedly agent-free control.
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().remove("JDK_JAVA_OPTIONS");
    builder.environment().remove("_JAVA_OPTIONS");
    Process child = builder.start();
    final int outputLimit = 1_048_576;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    AtomicBoolean overflow = new AtomicBoolean();
    AtomicReference<Throwable> readFailure = new AtomicReference<>();
    Thread reader =
        new Thread(
            () -> {
              try (var inputStream = child.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = inputStream.read(buffer)) >= 0; ) {
                  int remaining = outputLimit - captured.size();
                  int accepted = Math.min(count, remaining);
                  if (accepted > 0) captured.write(buffer, 0, accepted);
                  if (accepted != count) {
                    overflow.set(true);
                    child.destroyForcibly();
                    break;
                  }
                }
              } catch (Throwable failure) {
                readFailure.set(failure);
                child.destroyForcibly();
              }
            },
            "strict-loader-child-output");
    reader.setDaemon(true);
    reader.start();
    boolean done;
    try {
      done = child.waitFor(45, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      child.destroyForcibly();
      Thread.currentThread().interrupt();
      throw interrupted;
    }
    if (!done) {
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS), "strict-loader child could not be reaped");
    }
    reader.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(reader.isAlive(), "strict-loader output reader could not be stopped");
    String output = captured.toString(StandardCharsets.UTF_8);
    System.out.println("STRICT_CHILD_BEGIN " + mode + "\n" + output + "STRICT_CHILD_END " + mode);
    assertFalse(overflow.get(), "strict-loader child output exceeded 1 MiB");
    if (readFailure.get() != null)
      throw new AssertionError("strict-loader output read failed", readFailure.get());
    assertTrue(done, () -> "strict-loader child timed out: " + output);
    assertEquals(0, child.exitValue(), output);
    assertTrue(output.contains(expected), output);
    if (!mode.equals("positive")) assertFalse(output.contains("STRICT_TARGET_INVOKED"), output);
  }

  /** Each scenario runs in its own disposable JVM: the ownership failure latch is process-wide. */
  public static void main(String[] args) throws Exception {
    if (!Boolean.getBoolean("fray.strictLoaderExperiment.child") || args.length != 1) {
      throw new IllegalStateException("disposable child required");
    }
    String mode = args[0];
    byte[] bytes = fixture();
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    if (mode.equals("wrong-digest")) {
      try {
        CooperativeClosedGraph.verify(
            List.of(new PinnedCooperativeClass(ROOT, "0".repeat(64), bytes)), ROOT, "run");
        throw new AssertionError("wrong digest was accepted");
      } catch (IllegalArgumentException expected) {
        if (expected.getMessage() == null || !expected.getMessage().contains("digest"))
          throw expected;
        System.out.println("STRICT_EXPECTED_WRONG_DIGEST");
        return;
      }
    }
    VerifiedCooperativeGraph graph =
        CooperativeClosedGraph.verify(
            List.of(new PinnedCooperativeClass(ROOT, digest, bytes)), ROOT, "run");
    Map<String, Class<?>> trusted =
        Map.of(
            "java.lang.Object", Object.class,
            "java.lang.String", String.class,
            "org.pastalab.fray.runtime.Runtime", Runtime.class);
    try (FrayClassLoader loader =
        new FrayClassLoader(
            new URL[0],
            StrictLoaderAgentExperiment.class.getClassLoader(),
            false,
            graph,
            trusted)) {
      switch (mode) {
        case "missing-agent", "excluded" -> {
          try {
            loader.loadClass(DOT_ROOT);
            throw new AssertionError("untransformed target was accepted");
          } catch (IllegalStateException expected) {
            String required =
                mode.equals("missing-agent")
                    ? "missing or repeated app transformation"
                    : "unsupported owned app transform";
            if (expected.getMessage() == null || !expected.getMessage().contains(required))
              throw expected;
          }
          requirePoisoned(loader);
          System.out.println(
              mode.equals("missing-agent")
                  ? "STRICT_EXPECTED_MISSING_AGENT"
                  : "STRICT_EXPECTED_EXCLUSION");
        }
        case "untrusted" -> {
          try {
            loader.loadClass("java.util.ArrayList");
            throw new AssertionError("untrusted parent class was accepted");
          } catch (Exception expected) {
            if (!(expected instanceof ClassNotFoundException)
                || expected.getMessage() == null
                || !expected.getMessage().contains("untrusted class")) throw expected;
          }
          requirePoisoned(loader);
          try {
            loader.loadClass(DOT_ROOT);
            throw new AssertionError("poisoned loader accepted target");
          } catch (IllegalStateException expected) {
            if (expected.getMessage() == null
                || !expected.getMessage().contains("instrumentation failed")) throw expected;
          }
          System.out.println("STRICT_EXPECTED_UNTRUSTED");
        }
        case "boundary" -> {
          try {
            loader.checkFrozenBoundary();
            throw new AssertionError("boundary accepted without the entry class");
          } catch (IllegalStateException expected) {
            if (expected.getMessage() == null
                || !expected.getMessage().contains("boundary is incomplete")) throw expected;
          }
          requirePoisoned(loader);
          System.out.println("STRICT_EXPECTED_EARLY_BOUNDARY");
        }
        case "positive" -> {
          Class<?> fixture = loader.loadClass(DOT_ROOT);
          if (fixture.getClassLoader() != loader)
            throw new AssertionError("entry fell back to parent");
          Delegate previous = Runtime.LOCK_DELEGATE;
          CountingDelegate counting = new CountingDelegate();
          try {
            Runtime.LOCK_DELEGATE = counting;
            fixture.getMethod("run").invoke(null);
          } finally {
            Runtime.LOCK_DELEGATE = previous;
          }
          if (fixture.getField("value").getInt(null) != 1
              || counting.reads != 1
              || counting.writes != 1) {
            throw new AssertionError(
                "missing target effect or emitted hooks: reads="
                    + counting.reads
                    + ", writes="
                    + counting.writes);
          }
          System.out.println("STRICT_TARGET_INVOKED");
          loader.checkFrozenBoundary();
          System.out.println(
              "STRICT_POSITIVE_HOOKS reads=" + counting.reads + " writes=" + counting.writes);
        }
        default -> throw new IllegalArgumentException("unknown scenario");
      }
    }
  }

  private static void requirePoisoned(FrayClassLoader loader) {
    try {
      CoroutineInstrumentationOwners.checkHealthy(loader);
      throw new AssertionError("invalid strict epoch stayed healthy");
    } catch (IllegalStateException expected) {
      if (expected.getMessage() == null
          || !expected.getMessage().contains("instrumentation failed")) throw expected;
    }
  }

  /** The agent's strict path must instrument even this non-volatile static field. */
  private static byte[] fixture() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        ROOT,
        null,
        "java/lang/Object",
        null);
    writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "I", null, null).visitEnd();
    var method =
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
    method.visitCode();
    method.visitFieldInsn(Opcodes.GETSTATIC, ROOT, "value", "I");
    method.visitInsn(Opcodes.ICONST_1);
    method.visitInsn(Opcodes.IADD);
    method.visitFieldInsn(Opcodes.PUTSTATIC, ROOT, "value", "I");
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(2, 0);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /** Counts emitted hook calls, not Fray schedule decisions. */
  private static class CountingDelegate extends Delegate {
    int reads;
    int writes;

    @Override
    public void onStaticFieldRead(String owner, String name, String descriptor) {
      if (ROOT.equals(owner) && "value".equals(name) && "I".equals(descriptor)) reads++;
    }

    @Override
    public void onStaticFieldWrite(String owner, String name, String descriptor) {
      if (ROOT.equals(owner) && "value".equals(name) && "I".equals(descriptor)) writes++;
    }
  }
}
