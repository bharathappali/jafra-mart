package com.jaframart.pricing.engine;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TeardownGc {
  private static final long COALESCE_NS = 2_000_000_000L;

  private long lastGcNanos;
  private boolean lastRan;

  public synchronized boolean due() {
    long now = System.nanoTime();
    return lastGcNanos == 0 || now - lastGcNanos >= COALESCE_NS;
  }

  public synchronized boolean gcOnce() {
    long now = System.nanoTime();
    if (lastGcNanos > 0 && now - lastGcNanos < COALESCE_NS) {
      lastRan = true;
      return false;
    }
    System.gc();
    lastGcNanos = now;
    lastRan = true;
    return true;
  }

  public synchronized void gcNow() {
    System.gc();
    lastGcNanos = System.nanoTime();
    lastRan = true;
  }

  public synchronized boolean lastRan() {
    return lastRan;
  }
}
