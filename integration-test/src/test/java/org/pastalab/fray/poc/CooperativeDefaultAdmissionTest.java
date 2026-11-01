package org.pastalab.fray.poc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.pastalab.fray.instrumentation.base.CooperativeClosedGraph;
import org.pastalab.fray.instrumentation.base.CooperativeCoroutineAdmission;
import org.pastalab.fray.instrumentation.base.PinnedCooperativeClass;
import org.pastalab.fray.test.poc.CooperativeDefaultFixture;

/** Verifies actual compiled fixture classes, not a fabricated bytecode positive. */
public class CooperativeDefaultAdmissionTest {
  private static final String PACKAGE = "org/pastalab/fray/test/poc/";
  private static final String PREFIX = "CooperativeDefaultFixture";

  @Test
  void compiledKotlinFixturePassesSyntacticAdmission() throws Exception {
    var roots =
        Collections.list(
            CooperativeDefaultAdmissionTest.class.getClassLoader().getResources(PACKAGE));
    Map<String, byte[]> classes = new LinkedHashMap<>();
    for (URL root : roots) {
      if (root.getProtocol().equals("file")) {
        try (var files = Files.list(Path.of(root.toURI()))) {
          for (Path file : files.filter(Files::isRegularFile).toList()) {
            String name = file.getFileName().toString();
            if (isFixtureClass(name)) putUnique(classes, PACKAGE + name, Files.readAllBytes(file));
          }
        }
      } else if (root.getProtocol().equals("jar")) {
        JarURLConnection connection = (JarURLConnection) root.openConnection();
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
          var entries = jar.entries();
          while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory()
                && name.startsWith(PACKAGE)
                && isFixtureClass(name.substring(PACKAGE.length()))) {
              try (var input = jar.getInputStream(entry)) {
                putUnique(classes, name, input.readAllBytes());
              }
            }
          }
        }
      } else {
        throw new IllegalStateException("Unknown test classpath URL: " + root.getProtocol());
      }
    }
    assertFalse(classes.isEmpty(), "No compiled fixture classes found");
    assertTrue(classes.size() > 3, "Expected Kotlin-generated coroutine state machines");
    Set<String> names = Set.copyOf(classes.keySet());
    for (var entry : classes.entrySet()) {
      CooperativeCoroutineAdmission.verify(entry.getKey(), entry.getValue(), names);
    }
    // These digests are calculated here only to exercise graph semantics; they are not a trusted
    // production manifest and do not establish source, loader or receiver provenance.
    var manifest =
        classes.entrySet().stream()
            .map(
                entry -> {
                  try {
                    String digest =
                        HexFormat.of()
                            .formatHex(
                                MessageDigest.getInstance("SHA-256").digest(entry.getValue()));
                    return new PinnedCooperativeClass(entry.getKey(), digest, entry.getValue());
                  } catch (Exception error) {
                    throw new IllegalStateException(error);
                  }
                })
            .toList();
    for (String method : List.of("baseline", "mutex", "race")) {
      var graph = CooperativeClosedGraph.verify(manifest, PACKAGE + PREFIX, method);
      assertTrue(graph.classNames().equals(names), "closed graph must include every fixture class");
    }
  }

  @Test
  void ordinaryJvmBaselineAndMutexComplete() {
    CooperativeDefaultFixture.baseline();
    CooperativeDefaultFixture.mutex();
  }

  private static boolean isFixtureClass(String name) {
    return name.equals(PREFIX + ".class")
        || name.startsWith(PREFIX + "$") && name.endsWith(".class");
  }

  private static void putUnique(Map<String, byte[]> classes, String name, byte[] bytes) {
    String internalName = name.substring(0, name.length() - ".class".length());
    if (classes.putIfAbsent(internalName, bytes) != null) {
      throw new IllegalStateException("Duplicate compiled fixture class: " + internalName);
    }
  }
}
