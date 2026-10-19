package com.jaframart.scenario;

public record GuardStatus(boolean tripped, String reason, String trippedAt) {
  public static GuardStatus clear() {
    return new GuardStatus(false, null, null);
  }
}
