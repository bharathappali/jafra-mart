package com.jaframart.scenario;

import java.util.SplittableRandom;
import java.util.function.Consumer;

public final class ScenarioContext {
  private final long seed;
  private final SplittableRandom random;
  private final SafetyGuard guard;
  private final Consumer<String> log;

  public ScenarioContext(long seed, SafetyGuard guard, Consumer<String> log) {
    this.seed = seed;
    this.random = new SplittableRandom(seed);
    this.guard = guard;
    this.log = log;
  }

  public long seed() {
    return seed;
  }

  public SplittableRandom random() {
    return random;
  }

  public SafetyGuard guard() {
    return guard;
  }

  public void log(String message) {
    log.accept(message);
  }
}
