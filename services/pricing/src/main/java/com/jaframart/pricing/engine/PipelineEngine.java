package com.jaframart.pricing.engine;

import com.jaframart.pricing.rules.BaseRule;
import com.jaframart.pricing.rules.PriceRule;
import com.jaframart.pricing.rules.RoundingRule;
import com.jaframart.pricing.rules.VolumeRule;
import com.jaframart.scenario.ScenarioContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class PipelineEngine {
  private static final PriceRule RULE_A = BaseRule.INSTANCE;
  private static final PriceRule RULE_B = VolumeRule.INSTANCE;
  private static final PriceRule RULE_C = RoundingRule.INSTANCE;
  private static final int MAX_PIPES = 50_000;
  private static final int WARMUP = 22_000;

  private final TeardownGc teardownGc;
  private final Deque<Pipe> pipes = new ArrayDeque<>();
  private final AtomicInteger pipesCreated = new AtomicInteger();
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile Thread generator;
  private volatile double intensity;
  private volatile boolean frozen;
  private volatile boolean teardownGcFlag;

  @Inject
  public PipelineEngine(TeardownGc teardownGc) {
    this.teardownGc = teardownGc;
  }

  public void onStart(ScenarioContext ctx) {
    intensity = 0;
    frozen = false;
    if (running.compareAndSet(false, true)) {
      generator = new Thread(() -> loop(ctx), "pipeline-gen");
      generator.setDaemon(true);
      generator.start();
    }
  }

  public void onTick(ScenarioContext ctx, double ci) {
    intensity = ci;
    frozen = ctx.guard().growthFrozen();
    touchPipes();
  }

  public void reset() {
    running.set(false);
    Thread thread = generator;
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(2000);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
    generator = null;
    synchronized (pipes) {
      pipes.clear();
    }
    intensity = 0;
    frozen = false;
    teardownGc.gcNow();
    teardownGcFlag = teardownGc.lastRan();
  }

  public int pipesCreated() {
    return pipesCreated.get();
  }

  public int livePipes() {
    synchronized (pipes) {
      return pipes.size();
    }
  }

  public double codeCacheUsedPct() {
    long used = 0;
    long max = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      String name = pool.getName();
      if (name.contains("CodeHeap") || name.contains("Code Cache") || name.contains("CodeCache")) {
        used += pool.getUsage().getUsed();
        long poolMax = pool.getUsage().getMax();
        if (poolMax > 0) {
          max += poolMax;
        }
      }
    }
    if (max <= 0) {
      return 0;
    }
    return 100.0 * used / max;
  }

  public boolean teardownGc() {
    return teardownGcFlag;
  }

  private void loop(ScenarioContext ctx) {
    while (running.get()) {
      try {
        double ci = intensity;
        if (ci > 0 && !frozen) {
          int count = (int) Math.round(20 * ci);
          long started = System.nanoTime();
          for (int i = 0; i < count; i++) {
            createAndWarm(ctx);
          }
          trimToTarget(ci);
          long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
          if (elapsedMs < 1000L) {
            Thread.sleep(1000L - elapsedMs);
          }
        } else {
          Thread.sleep(1000L);
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable ex) {
        ctx.log("pipeline-gen error: " + ex.getMessage());
      }
    }
  }

  private void createAndWarm(ScenarioContext ctx) {
    try {
      Pipe pipe = HiddenClassFactory.pipe();
      for (int i = 0; i < WARMUP; i++) {
        pipe.run(RULE_A, 1000L + i);
      }
      for (int i = 0; i < WARMUP; i++) {
        pipe.run((i & 1) == 0 ? RULE_B : RULE_C, 1000L + i);
      }
      pipesCreated.incrementAndGet();
      synchronized (pipes) {
        pipes.addLast(pipe);
        while (pipes.size() > MAX_PIPES) {
          pipes.removeFirst();
        }
      }
    } catch (Throwable ex) {
      ctx.log("pipe create failed: " + ex.getMessage());
    }
  }

  private void trimToTarget(double ci) {
    double occupancy = codeCacheUsedPct() / 100.0;
    // Allocation fails around 83%, under the old 0.90 line, and pipesCreated froze.
    // Trim at 82% and collect, but only on a due GC (every 2s) so several ticks
    // cannot drain the cache between collections. One drop is ~4 points.
    double threshold = Math.min(0.82, 0.90 * ci);
    if (occupancy < threshold || !teardownGc.due()) {
      return;
    }
    synchronized (pipes) {
      if (pipes.isEmpty()) {
        return;
      }
      int drop = Math.max(1, pipes.size() / 15);
      for (int i = 0; i < drop && !pipes.isEmpty(); i++) {
        pipes.removeFirst();
      }
    }
    teardownGc.gcOnce();
  }

  private void touchPipes() {
    Pipe[] snapshot;
    synchronized (pipes) {
      snapshot = pipes.toArray(Pipe[]::new);
    }
    for (Pipe pipe : snapshot) {
      pipe.run(RULE_A, 42L);
    }
  }
}
