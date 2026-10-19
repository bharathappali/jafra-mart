package com.jaframart.scenario;

public final class GuardLimits {
  private GuardLimits() {}

  public static final double HEAP = 0.88;
  public static final double METASPACE = 0.75;
  public static final double CODE_CACHE = 0.95;
  public static final double DIRECT = 0.90;
  public static final int THREADS = 700;
  public static final double WORKING_SET = 0.90;
  public static final double UNTRIP = 0.95;
  public static final long WINDOW_MS = 10_000L;
}
