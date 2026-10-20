package com.jaframart.orders.notify;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FulfillmentNotifier {
  private static final int BURST_ORDERS = 20;
  private static final long BURST_WINDOW_MS = 2_000L;
  private static final long BURST_DETECT_COOLDOWN_MS = 5_000L;
  private static final int MAX_POOLS = 40;

  private final ThreadPoolExecutor baseline;
  private final Deque<Long> recentOrders = new ArrayDeque<>();
  private final List<ThreadPoolExecutor> leakedPools = new ArrayList<>();
  private final Object lock = new Object();

  private volatile double intensity;
  private volatile boolean growthFrozen;
  private long lastBurstDetectMs;
  private int poolSeq;
  private int reservedPools;

  public FulfillmentNotifier() {
    AtomicInteger n = new AtomicInteger();
    ThreadFactory factory = r -> {
      Thread t = new Thread(r, "fulfillment-notify-" + n.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
    this.baseline = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(1_000),
        factory,
        new ThreadPoolExecutor.DiscardOldestPolicy());
  }

  public void onOrderPlaced(Object order) {
    baseline.execute(FulfillmentNotifier::notifyWarehouse);
    long now = System.currentTimeMillis();
    BurstPlan plan;
    synchronized (lock) {
      recentOrders.addLast(now);
      pruneOrders(now);
      plan = planBurstLocked(now);
    }
    attach(plan);
  }

  /**
   * Leak site for S04 JFR stacks. Creates up to 2 pools per detection, capped by target.
   * Threads are started outside the notifier lock so a slow {@code pthread_create} cannot
   * stall the scenario clock.
   * @return number of pools created
   */
  int onBurst() {
    BurstPlan plan;
    synchronized (lock) {
      plan = planBurstLocked(System.currentTimeMillis());
    }
    return attach(plan);
  }

  private void pruneOrders(long now) {
    while (!recentOrders.isEmpty() && now - recentOrders.peekFirst() > BURST_WINDOW_MS) {
      recentOrders.removeFirst();
    }
  }

  private BurstPlan planBurstLocked(long now) {
    pruneOrders(now);
    if (intensity <= 0 || growthFrozen) {
      return BurstPlan.NONE;
    }
    if (recentOrders.size() < BURST_ORDERS) {
      return BurstPlan.NONE;
    }
    if (now - lastBurstDetectMs < BURST_DETECT_COOLDOWN_MS) {
      return BurstPlan.NONE;
    }
    int target = targetPools();
    int room = Math.min(2, Math.min(target, MAX_POOLS) - leakedPools.size() - reservedPools);
    if (room <= 0) {
      return BurstPlan.NONE;
    }
    int firstId = poolSeq + 1;
    poolSeq += room;
    reservedPools += room;
    lastBurstDetectMs = now;
    return new BurstPlan(room, firstId);
  }

  private int attach(BurstPlan plan) {
    if (plan.room <= 0) {
      return 0;
    }
    List<ThreadPoolExecutor> created = new ArrayList<>();
    try {
      for (int i = 0; i < plan.room; i++) {
        if (!pidsAllow(8)) {
          growthFrozen = true;
          break;
        }
        try {
          created.add(newBurstPool(plan.firstId + i));
        } catch (OutOfMemoryError ex) {
          growthFrozen = true;
          break;
        }
      }
    } finally {
      synchronized (lock) {
        leakedPools.addAll(created);
        reservedPools -= plan.room;
        if (reservedPools < 0) {
          reservedPools = 0;
        }
      }
    }
    return created.size();
  }

  private static boolean pidsAllow(int extra) {
    try {
      String maxText = Files.readString(Path.of("/sys/fs/cgroup/pids.max")).trim();
      if ("max".equals(maxText)) {
        return true;
      }
      long max = Long.parseLong(maxText);
      long current = Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/pids.current")).trim());
      return current + extra + 24 < max;
    } catch (Exception ex) {
      return true;
    }
  }

  private static ThreadPoolExecutor newBurstPool(int poolId) {
    AtomicInteger idx = new AtomicInteger();
    ThreadFactory factory = r -> {
      Thread t = new Thread(r, "notify-burst-" + poolId + "-" + idx.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
        8, 8, 0L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        factory);
    for (int i = 0; i < 8; i++) {
      pool.execute(FulfillmentNotifier::notifyWarehouse);
    }
    return pool;
  }

  private static void notifyWarehouse() {
    try {
      Thread.sleep(2);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  public void setScenarioIntensity(double ci) {
    BurstPlan plan;
    synchronized (lock) {
      this.intensity = ci;
      trimToTarget();
      // Re-check after intensity rises so a burst that arrived at ci=0 still leaks pools.
      plan = planBurstLocked(System.currentTimeMillis());
    }
    attach(plan);
  }

  public void setGrowthFrozen(boolean frozen) {
    this.growthFrozen = frozen;
  }

  private void trimToTarget() {
    int target = targetPools();
    while (leakedPools.size() > target) {
      ThreadPoolExecutor newest = leakedPools.remove(leakedPools.size() - 1);
      newest.shutdown();
    }
  }

  private int targetPools() {
    return (int) Math.round(40.0 * intensity);
  }

  public void resetToBaseline() {
    synchronized (lock) {
      intensity = 0;
      growthFrozen = false;
      lastBurstDetectMs = 0;
      reservedPools = 0;
      recentOrders.clear();
      for (ThreadPoolExecutor pool : leakedPools) {
        pool.shutdown();
      }
      leakedPools.clear();
      poolSeq = 0;
    }
  }

  public int leakedPools() {
    synchronized (lock) {
      return leakedPools.size();
    }
  }

  /** JVM live thread count (baseline ≈ 60 + leaked burst-pool threads). */
  public int threadCount() {
    return ManagementFactory.getThreadMXBean().getThreadCount();
  }

  private record BurstPlan(int room, int firstId) {
    static final BurstPlan NONE = new BurstPlan(0, 0);
  }

  @PreDestroy
  public void shutdown() {
    resetToBaseline();
    baseline.shutdown();
  }
}
