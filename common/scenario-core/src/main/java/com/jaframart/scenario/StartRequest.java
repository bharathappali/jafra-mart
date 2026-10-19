package com.jaframart.scenario;

public record StartRequest(Double intensity, Integer rampSeconds, Integer holdSeconds,
                           Integer recoverSeconds, Long seed, String runId) {
  public static StartRequest empty() {
    return new StartRequest(null, null, null, null, null, null);
  }
}
