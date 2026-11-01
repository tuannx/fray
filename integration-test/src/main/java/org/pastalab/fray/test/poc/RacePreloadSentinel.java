package org.pastalab.fray.test.poc;

/** Diagnostic host-property sentinel, only touched by the opt-in race preload experiment. */
public final class RacePreloadSentinel {
  static {
    if (!"true".equals(System.getProperty("fray.experiment.racePreload.setupEnded"))) {
      throw new IllegalStateException("diagnostic initializer ran before setup ended");
    }
    System.setProperty("fray.experiment.racePreload.initialized", "true");
  }

  private RacePreloadSentinel() {}

  public static void touch() {}
}
