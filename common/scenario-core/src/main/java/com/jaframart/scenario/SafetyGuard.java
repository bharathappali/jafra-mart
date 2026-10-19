package com.jaframart.scenario;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;

public final class SafetyGuard {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  public record Snapshot(long heapUsed, long heapMax, long metaspaceUsed, long metaspaceMax,
                         long codeUsed, long codeMax, long directUsed, long directMax, int threads,
                         Long workingSetBytes, Long memoryMaxBytes) {}

  public interface Sampler {
    Snapshot sample();
  }

  private final Sampler sampler;
  private final Deque<HeapPoint> heapWindow = new ArrayDeque<>();
  private boolean tripped;
  private String reason;
  private Instant trippedAt;
  private Instant underSince;
  private long offHeapBytes;
  private long offHeapCap;

  public SafetyGuard(Sampler sampler) {
    this.sampler = sampler;
  }

  public static SafetyGuard installed() {
    return new SafetyGuard(SafetyGuard::sampleLive);
  }

  public synchronized void reportOffHeap(long bytes, long cap) {
    this.offHeapBytes = bytes;
    this.offHeapCap = cap;
  }

  public synchronized boolean growthFrozen() {
    return tripped;
  }

  public synchronized GuardStatus status() {
    if (!tripped) {
      return GuardStatus.clear();
    }
    return new GuardStatus(true, reason, trippedAt == null ? null : ISO.format(trippedAt));
  }

  public synchronized void sample(Instant now) {
    Snapshot snap = sampler.sample();
    rememberHeap(now, snap.heapUsed());
    String trip = firstTrip(snap);
    if (trip != null) {
      if (!tripped) {
        tripped = true;
        reason = trip;
        trippedAt = now;
        System.getLogger(SafetyGuard.class.getName()).log(System.Logger.Level.WARNING, "GUARD_TRIPPED " + trip);
      }
      underSince = null;
      return;
    }
    if (!tripped) {
      return;
    }
    if (!allUnderUntrip(snap)) {
      underSince = null;
      return;
    }
    if (underSince == null) {
      underSince = now;
    }
    if (now.toEpochMilli() - underSince.toEpochMilli() >= GuardLimits.WINDOW_MS) {
      tripped = false;
      reason = null;
      trippedAt = null;
      underSince = null;
    }
  }

  private void rememberHeap(Instant now, long used) {
    heapWindow.addLast(new HeapPoint(now.toEpochMilli(), used));
    long cutoff = now.toEpochMilli() - GuardLimits.WINDOW_MS;
    while (!heapWindow.isEmpty() && heapWindow.peekFirst().atMs() < cutoff) {
      heapWindow.removeFirst();
    }
  }

  private long heapMin() {
    long min = Long.MAX_VALUE;
    for (HeapPoint point : heapWindow) {
      min = Math.min(min, point.used());
    }
    return min == Long.MAX_VALUE ? 0L : min;
  }

  private String firstTrip(Snapshot snap) {
    if (over(heapMin(), snap.heapMax(), GuardLimits.HEAP)) {
      return "heap";
    }
    if (over(snap.metaspaceUsed(), snap.metaspaceMax(), GuardLimits.METASPACE)) {
      return "metaspace";
    }
    if (over(snap.codeUsed(), snap.codeMax(), GuardLimits.CODE_CACHE)) {
      return "code-cache";
    }
    if (over(snap.directUsed(), snap.directMax(), GuardLimits.DIRECT)) {
      return "direct";
    }
    if (offHeapCap > 0 && offHeapBytes > offHeapCap) {
      return "off-heap";
    }
    if (snap.threads() > GuardLimits.THREADS) {
      return "threads";
    }
    if (snap.workingSetBytes() != null && snap.memoryMaxBytes() != null
        && snap.memoryMaxBytes() > 0
        && snap.workingSetBytes() > GuardLimits.WORKING_SET * snap.memoryMaxBytes()) {
      return "container-memory";
    }
    return null;
  }

  private boolean allUnderUntrip(Snapshot snap) {
    if (!under(heapMin(), snap.heapMax(), GuardLimits.HEAP)) {
      return false;
    }
    if (!under(snap.metaspaceUsed(), snap.metaspaceMax(), GuardLimits.METASPACE)) {
      return false;
    }
    if (!under(snap.codeUsed(), snap.codeMax(), GuardLimits.CODE_CACHE)) {
      return false;
    }
    if (!under(snap.directUsed(), snap.directMax(), GuardLimits.DIRECT)) {
      return false;
    }
    if (offHeapCap > 0 && offHeapBytes > GuardLimits.UNTRIP * offHeapCap) {
      return false;
    }
    if (snap.threads() > GuardLimits.UNTRIP * GuardLimits.THREADS) {
      return false;
    }
    if (snap.workingSetBytes() != null && snap.memoryMaxBytes() != null && snap.memoryMaxBytes() > 0
        && snap.workingSetBytes() > GuardLimits.UNTRIP * GuardLimits.WORKING_SET * snap.memoryMaxBytes()) {
      return false;
    }
    return true;
  }

  private static boolean over(long used, long max, double fraction) {
    return max > 0 && used > fraction * max;
  }

  private static boolean under(long used, long max, double fraction) {
    if (max <= 0) {
      return true;
    }
    return used < GuardLimits.UNTRIP * fraction * max;
  }

  static Snapshot sampleLive() {
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long metaUsed = 0, metaMax = 0, codeUsed = 0, codeMax = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      MemoryUsage usage = pool.getUsage();
      if (usage == null) {
        continue;
      }
      if ("Metaspace".equals(pool.getName())) {
        metaUsed = usage.getUsed();
        metaMax = usage.getMax();
      } else if ("CodeCache".equals(pool.getName())) {
        codeUsed = usage.getUsed();
        codeMax = usage.getMax();
      }
    }
    long directUsed = 0;
    long directMax = vmOptionBytes("MaxDirectMemorySize");
    for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
      if ("direct".equals(pool.getName())) {
        directUsed = pool.getMemoryUsed();
      }
    }
    int threads = ManagementFactory.getThreadMXBean().getThreadCount();
    CgroupMemory.Reading cgroup = CgroupMemory.read();
    Long workingSet = cgroup == null ? null : cgroup.workingSet();
    Long memoryMax = cgroup == null ? null : cgroup.max();
    return new Snapshot(heap.getUsed(), heap.getMax(), metaUsed, metaMax, codeUsed, codeMax,
        directUsed, directMax, threads, workingSet, memoryMax);
  }

  private static long vmOptionBytes(String name) {
    try {
      var diagnostic = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
      return Long.parseLong(diagnostic.getVMOption(name).getValue());
    } catch (RuntimeException ex) {
      return 0L;
    }
  }

  private record HeapPoint(long atMs, long used) {}
}
