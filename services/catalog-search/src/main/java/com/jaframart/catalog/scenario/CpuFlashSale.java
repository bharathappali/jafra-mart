package com.jaframart.catalog.scenario;

import com.jaframart.catalog.search.SearchService;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class CpuFlashSale implements Scenario {
  private final SearchService search;

  public CpuFlashSale(SearchService search) {
    this.search = search;
  }

  public String id() { return "S01"; }
  public String name() { return "cpu-flash-sale"; }
  public List<Integer> subsystems() { return List.of(1); }
  public int defaultRampSeconds() { return 60; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    int expansions = search.fuzzyExpansions();
    return Map.of(
        "prefilter", search.prefilter(),
        "fuzzyExpansions", expansions,
        "extraSearchRps", search.intensity() <= 0 ? 0 : 60);
  }

  public void onStart(ScenarioContext ctx) {
    search.setIntensity(0);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    search.setIntensity(currentIntensity);
  }

  public void onReset(ScenarioContext ctx) {
    search.setIntensity(0);
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "searchCpuMsP50", search.searchCpuMsP50(),
        "fuzzySearches", search.fuzzySearches(),
        "fuzzyExpansions", search.fuzzyExpansions());
  }
}
