package com.jaframart.recs.scenario;

import com.jaframart.recs.cache.SimilarityCache;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class HeapRecsCache implements Scenario {
  private final SimilarityCache cache;

  public HeapRecsCache(SimilarityCache cache) {
    this.cache = cache;
  }

  public String id() { return "S05"; }
  public String name() { return "heap-recs-cache"; }
  public List<Integer> subsystems() { return List.of(2, 3); }
  public int defaultRampSeconds() { return 120; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    return Map.of(
        "embeddingDim", 16_384,
        "maxEntriesBaseline", SimilarityCache.MAX_ENTRIES,
        "capFraction", 0.62);
  }

  public void onStart(ScenarioContext ctx) {
    cache.resetToBaseline();
    cache.setScenarioIntensity(0);
    cache.setGrowthFrozen(false);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    cache.setGrowthFrozen(ctx.guard().growthFrozen());
    cache.setScenarioIntensity(currentIntensity);
  }

  public void onReset(ScenarioContext ctx) {
    cache.resetToBaseline();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "cacheBytes", cache.cacheBytes(),
        "cacheEntries", cache.cacheEntries());
  }
}
