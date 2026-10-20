package com.jaframart.orders.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FulfillmentNotifierTest {
  @Test
  void burstCreatesPoolsWhileIntensityPositive() throws Exception {
    FulfillmentNotifier notifier = new FulfillmentNotifier();
    notifier.setScenarioIntensity(1.0);
    for (int i = 0; i < 25; i++) {
      notifier.onOrderPlaced("o-" + i);
    }
    Thread.sleep(50);
    assertTrue(notifier.leakedPools() >= 2);
    assertTrue(notifier.threadCount() >= 16); // JVM threads include at least the new pool workers

    notifier.setScenarioIntensity(0);
    assertEquals(0, notifier.leakedPools());
    notifier.shutdown();
  }
}
