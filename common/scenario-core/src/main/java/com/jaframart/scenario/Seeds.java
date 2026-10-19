package com.jaframart.scenario;

import java.util.SplittableRandom;

public final class Seeds {
  private Seeds() {}

  public static long derive(long globalSeed, String id) {
    return new SplittableRandom(globalSeed ^ id.hashCode()).nextLong();
  }

  public static long globalSeed() {
    String raw = System.getenv("JAFRA_MART_SEED");
    if (raw == null || raw.isBlank()) {
      return 20261019L;
    }
    return Long.parseLong(raw.trim());
  }
}
