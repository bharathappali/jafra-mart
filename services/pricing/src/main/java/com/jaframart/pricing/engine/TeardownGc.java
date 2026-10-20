package com.jaframart.pricing.engine;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TeardownGc {
  private static final long COALESCE_NS = 2_000_000_000L;

  private long lastGcNanos;
  private boolean lastRan;

  public synchronized void gcOnce() {
    long now = System.nanoTime();
    if (lastGcNanos > 0 && now - lastGcNanos < COALESCE_NS) {
      lastRan = true;
      return;
    }
    System.gc();
    lastGcNanos = now;
    lastRan = true;
  }

  public synchronized boolean lastRan() {
    return lastRan;
  }
}
