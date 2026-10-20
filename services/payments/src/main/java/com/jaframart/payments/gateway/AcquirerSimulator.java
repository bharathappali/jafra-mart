package com.jaframart.payments.gateway;

import com.jaframart.scenario.Seeds;

import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class AcquirerSimulator {
  private final long seed;
  private final AtomicLong authorizations = new AtomicLong();
  private volatile int baseLatencyMs = 40;

  public AcquirerSimulator() {
    this(Seeds.globalSeed());
  }

  public AcquirerSimulator(long seed) {
    this.seed = seed;
  }

  public void setBaseLatencyMs(int baseLatencyMs) {
    this.baseLatencyMs = Math.max(0, baseLatencyMs);
  }

  public int baseLatencyMs() {
    return baseLatencyMs;
  }

  public long authorizations() {
    return authorizations.get();
  }

  public int await(String orderNumber) {
    SplittableRandom random = new SplittableRandom(seed ^ (orderNumber == null ? 0 : orderNumber.hashCode()));
    int jitter = random.nextInt(21) - 10;
    int sleepMs = Math.max(0, baseLatencyMs + jitter);
    try {
      Thread.sleep(sleepMs);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    authorizations.incrementAndGet();
    return sleepMs;
  }

  public void reset() {
    baseLatencyMs = 40;
    authorizations.set(0);
  }
}
