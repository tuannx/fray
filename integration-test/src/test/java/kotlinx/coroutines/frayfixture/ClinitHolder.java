package kotlinx.coroutines.frayfixture;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class ClinitHolder {
  public static final AtomicIntegerFieldUpdater<ClinitHolder> UPDATER;
  public volatile int value;

  static {
    ClinitState.ready.countDown();
    UPDATER = AtomicIntegerFieldUpdater.newUpdater(ClinitHolder.class, "value");
  }

  public static int touch() {
    return 1;
  }
}
