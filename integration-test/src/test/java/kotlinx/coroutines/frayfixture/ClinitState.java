package kotlinx.coroutines.frayfixture;

import java.util.concurrent.CountDownLatch;

public class ClinitState {
  public static final CountDownLatch ready = new CountDownLatch(1);
  public static volatile boolean completed;
}
