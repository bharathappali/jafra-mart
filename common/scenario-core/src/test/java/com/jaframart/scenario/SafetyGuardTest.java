package com.jaframart.scenario;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyGuardTest {
  @Test
  void tripsAndUntripsWithFakeSamples() {
    AtomicReference<SafetyGuard.Snapshot> current = new AtomicReference<>(
        new SafetyGuard.Snapshot(10, 1000, 10, 1000, 10, 1000, 10, 1000, 10, null, null));
    SafetyGuard guard = new SafetyGuard(current::get);
    Instant t0 = Instant.parse("2026-10-19T12:00:00Z");
    guard.sample(t0);
    assertFalse(guard.status().tripped());

    current.set(new SafetyGuard.Snapshot(10, 1000, 800, 1000, 10, 1000, 10, 1000, 10, null, null));
    guard.sample(t0.plusMillis(250));
    assertTrue(guard.status().tripped());
    assertEquals("metaspace", guard.status().reason());
    assertTrue(guard.growthFrozen());

    current.set(new SafetyGuard.Snapshot(10, 1000, 10, 1000, 10, 1000, 10, 1000, 10, null, null));
    Instant under = t0.plusMillis(500);
    guard.sample(under);
    assertTrue(guard.status().tripped());
    guard.sample(under.plusMillis(GuardLimits.WINDOW_MS));
    assertFalse(guard.status().tripped());
    assertNull(guard.status().reason());
  }

  @Test
  void heapThreadsOffHeapAndMissingCgroup() {
    AtomicReference<SafetyGuard.Snapshot> current = new AtomicReference<>(
        new SafetyGuard.Snapshot(900, 1000, 0, 1000, 0, 1000, 0, 1000, 10, null, null));
    SafetyGuard guard = new SafetyGuard(current::get);
    Instant now = Instant.parse("2026-10-19T12:00:00Z");
    guard.sample(now);
    assertEquals("heap", guard.status().reason());

    current.set(new SafetyGuard.Snapshot(0, 1000, 0, 1000, 0, 1000, 0, 1000, 701, null, null));
    SafetyGuard threads = new SafetyGuard(current::get);
    threads.sample(now);
    assertEquals("threads", threads.status().reason());

    SafetyGuard offHeap = new SafetyGuard(() ->
        new SafetyGuard.Snapshot(0, 1000, 0, 1000, 0, 1000, 0, 1000, 1, null, null));
    offHeap.reportOffHeap(111, 110);
    offHeap.sample(now);
    assertEquals("off-heap", offHeap.status().reason());

    SafetyGuard cgroup = new SafetyGuard(() ->
        new SafetyGuard.Snapshot(0, 1000, 0, 1000, 0, 1000, 0, 1000, 1, null, null));
    cgroup.sample(now);
    assertFalse(cgroup.status().tripped());
  }
}
