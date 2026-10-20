package com.jaframart.reports.reconcile;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * S13 safepoint-ttsp workload: counted-loop checksum plus young-GC allocation.
 * Do not set UseCountedLoopSafepoints or LoopStripMiningIter.
 */
@Component
public class ReconciliationJob implements AutoCloseable {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final int LEDGER_LEN = 4_194_304;
  private static final long ALLOC_BYTES_PER_SEC = 24L * 1024 * 1024;
  private static final int CHUNK = 65_536;

  private final Object lock = new Object();
  private volatile double intensity;
  private volatile boolean running = true;
  private int[] ledger;
  private int reps = 1;
  private Thread reconcileThread;
  private Thread allocThread;
  private Thread heartbeatThread;
  private final AtomicLong checksumCallMs = new AtomicLong();
  private final LongAdder heartbeatStallsOver100ms = new LongAdder();
  private volatile String lastReconciliation;

  public ReconciliationJob() {
    heartbeatThread = new Thread(this::heartbeatLoop, "reconcile-heartbeat");
    heartbeatThread.setDaemon(true);
    heartbeatThread.start();
  }

  public void setIntensity(double ci) {
    synchronized (lock) {
      double previous = intensity;
      intensity = Math.max(0, ci);
      if (intensity > 0 && previous <= 0) {
        startWorkers();
      } else if (intensity <= 0 && previous > 0) {
        stopWorkers();
      }
    }
  }

  public long heartbeatStallsOver100ms() {
    return heartbeatStallsOver100ms.sum();
  }

  public long checksumCallMs() {
    return checksumCallMs.get();
  }

  public String lastReconciliation() {
    return lastReconciliation;
  }

  public void resetMetrics() {
    heartbeatStallsOver100ms.reset();
    checksumCallMs.set(0);
  }

  private void startWorkers() {
    ledger = new int[LEDGER_LEN];
    for (int i = 0; i < ledger.length; i++) {
      ledger[i] = i * 31;
    }
    calibrate(intensity);
    reconcileThread = new Thread(this::reconcileLoop, "reconcile-1");
    reconcileThread.setDaemon(true);
    reconcileThread.start();
    allocThread = new Thread(this::allocLoop, "reconcile-alloc");
    allocThread.setDaemon(true);
    allocThread.start();
  }

  private void stopWorkers() {
    Thread r = reconcileThread;
    Thread a = allocThread;
    reconcileThread = null;
    allocThread = null;
    if (r != null) {
      r.interrupt();
    }
    if (a != null) {
      a.interrupt();
    }
    ledger = null;
  }

  private void calibrate(double ci) {
    int[] a = ledger;
    if (a == null) {
      return;
    }
    long t0 = System.nanoTime();
    checksum(a, 1);
    long passNs = Math.max(1, System.nanoTime() - t0);
    double targetNs = 250_000_000d * ci;
    reps = Math.max(1, (int) Math.round(targetNs / passNs));
  }

  private void reconcileLoop() {
    while (running && intensity > 0 && Thread.currentThread() == reconcileThread) {
      try {
        calibrate(intensity);
        int[] a = ledger;
        if (a == null) {
          return;
        }
        long t0 = System.nanoTime();
        long sum = checksum(a, reps);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        checksumCallMs.set(ms);
        lastReconciliation = ISO.format(Instant.now()) + " sum=" + sum;
      } catch (Throwable ignored) {
        // keep looping
      }
    }
  }

  /** Hot counted loops — must stay int-indexed for Parallel GC safepoint behavior. */
  static long checksum(int[] a, int reps) {
    long s = 0;
    for (int r = 0; r < reps; r++) {
      for (int i = 0; i < a.length; i++) {
        s += a[i] * 31L + i;
      }
    }
    return s;
  }

  private void allocLoop() {
    long next = System.nanoTime();
    while (running && intensity > 0 && Thread.currentThread() == allocThread) {
      long budget = ALLOC_BYTES_PER_SEC;
      long allocated = 0;
      while (allocated < budget && intensity > 0) {
        byte[] junk = new byte[CHUNK];
        junk[0] = 1;
        allocated += CHUNK;
      }
      next += 1_000_000_000L;
      long sleep = (next - System.nanoTime()) / 1_000_000L;
      if (sleep > 0) {
        try {
          Thread.sleep(sleep);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
      } else {
        next = System.nanoTime();
      }
    }
  }

  private void heartbeatLoop() {
    long last = System.nanoTime();
    while (running) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      long now = System.nanoTime();
      long gapMs = (now - last) / 1_000_000L;
      if (gapMs > 100) {
        heartbeatStallsOver100ms.increment();
      }
      last = now;
    }
  }

  @Override
  @PreDestroy
  public void close() {
    running = false;
    stopWorkers();
    if (heartbeatThread != null) {
      heartbeatThread.interrupt();
    }
  }
}
