package org.pastalab.fray.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local control: select two unchanged dynamic fixtures with their original upstream configuration. */
public class UpstreamNetworkControl {
  @TempDir Path tempDir;

  @Test
  void asyncClientConnectNoServerException() throws Throwable {
    runOriginal("org.pastalab.fray.test.core.fail.network.AsyncClientConnectNoServerException");
  }

  @Test
  void syncClientExceptionWithoutServer() throws Throwable {
    runOriginal("org.pastalab.fray.test.core.fail.network.SyncClientExceptionWithoutServer");
  }

  private void runOriginal(String className) throws Throwable {
    FrayTestCase original = new FrayTestCase();
    original.tempDir = tempDir;
    List<DynamicTest> matches =
        original.testCases().stream()
            .filter(test -> test.getDisplayName().equals("Test: " + className))
            .toList();
    assertEquals(1, matches.size(), "Expected exactly one upstream fixture");
    matches.get(0).getExecutable().execute();
  }
}
