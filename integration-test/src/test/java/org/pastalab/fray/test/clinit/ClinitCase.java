package org.pastalab.fray.test.clinit;

import kotlinx.coroutines.frayfixture.ClinitHolder;
import kotlinx.coroutines.frayfixture.ClinitState;

public class ClinitCase {
  public static void run() throws Exception {
    ClinitState.ready.getCount();
    Thread waiter =
        new Thread(
            () -> {
              try {
                ClinitState.ready.await();
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              ClinitHolder.touch();
            },
            "clinit-waiter");
    Thread owner = new Thread(ClinitHolder::touch, "clinit-owner");
    waiter.start();
    owner.start();
    waiter.join();
    owner.join();
    ClinitState.completed = true;
  }
}
