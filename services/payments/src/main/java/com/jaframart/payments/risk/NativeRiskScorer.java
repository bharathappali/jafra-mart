package com.jaframart.payments.risk;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeRiskScorer {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final MethodHandle QSORT;
  private static final MethodHandle COMPARE_UPCALL;
  private static final ScheduledExecutorService LOOKUP =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "velocity-lookup");
        t.setDaemon(true);
        return t;
      });

  private static final ThreadLocal<AtomicBoolean> BLOCKING = ThreadLocal.withInitial(AtomicBoolean::new);

  static {
    try {
      MemorySegment qsortSymbol = LINKER.defaultLookup().find("qsort").orElseThrow();
      QSORT = LINKER.downcallHandle(
          qsortSymbol,
          FunctionDescriptor.ofVoid(
              ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
      COMPARE_UPCALL = MethodHandles.lookup().findStatic(
          NativeRiskScorer.class,
          "compare",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
    } catch (ReflectiveOperationException | IllegalArgumentException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private NativeRiskScorer() {}

  public static int score(int[] signals, boolean blocking) {
    BLOCKING.get().set(blocking);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment array = arena.allocate((long) signals.length * Integer.BYTES, Integer.BYTES);
      for (int i = 0; i < signals.length; i++) {
        array.setAtIndex(ValueLayout.JAVA_INT, i, signals[i]);
      }
      MemorySegment stub = LINKER.upcallStub(
          COMPARE_UPCALL,
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
          arena);
      try {
        QSORT.invokeExact(array, (long) signals.length, (long) Integer.BYTES, stub);
      } catch (Throwable t) {
        throw new IllegalStateException("qsort failed", t);
      }
      int[] sorted = new int[signals.length];
      for (int i = 0; i < signals.length; i++) {
        sorted[i] = array.getAtIndex(ValueLayout.JAVA_INT, i);
      }
      return RiskScorer.score(sorted);
    } finally {
      BLOCKING.remove();
    }
  }

  @SuppressWarnings("unused")
  private static int compare(MemorySegment a, MemorySegment b) {
    if (BLOCKING.get().get()) {
      CompletableFuture<Void> future = new CompletableFuture<>();
      LOOKUP.schedule(() -> future.complete(null), 40, TimeUnit.MILLISECONDS);
      try {
        future.get();
      } catch (Exception ex) {
        Thread.currentThread().interrupt();
      }
    }
    int left = a.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
    int right = b.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
    return Integer.compare(left, right);
  }
}
