package com.jaframart.catalog.scenario;

import com.jaframart.catalog.thumb.ThumbnailCache;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class NativeThumbnails implements Scenario {
  private static final long GUARD_CAP = 110L * 1024L * 1024L;
  private final ThumbnailCache cache;

  public NativeThumbnails(ThumbnailCache cache) {
    this.cache = cache;
  }

  public String id() { return "S10"; }
  public String name() { return "native-thumbnails"; }
  public List<Integer> subsystems() { return List.of(6, 7); }
  public int defaultRampSeconds() { return 60; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 60; }
  public Map<String, Object> params() { return Map.of("capMiB", 104, "diskMiB", 128); }

  public void onStart(ScenarioContext ctx) {
    cache.applyIntensity(0, false);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    cache.applyIntensity(currentIntensity, ctx.guard().growthFrozen());
    ctx.guard().reportOffHeap(cache.offHeapBytes(), GUARD_CAP);
  }

  public void onReset(ScenarioContext ctx) {
    cache.resetBaseline();
    ctx.guard().reportOffHeap(0, 0);
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "offHeapBytes", cache.offHeapBytes(),
        "diskBytes", cache.diskBytes(),
        "cacheEntries", cache.cacheEntries());
  }
}
