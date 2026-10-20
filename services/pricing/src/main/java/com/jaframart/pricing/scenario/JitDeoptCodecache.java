package com.jaframart.pricing.scenario;

import com.jaframart.pricing.engine.PipelineEngine;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class JitDeoptCodecache implements Scenario {
  private final PipelineEngine engine;

  public JitDeoptCodecache(PipelineEngine engine) {
    this.engine = engine;
  }

  public String id() { return "S09"; }
  public String name() { return "jit-deopt-codecache"; }
  public List<Integer> subsystems() { return List.of(12, 5); }
  public int defaultRampSeconds() { return 60; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    return Map.of("teardownGc", engine.teardownGc());
  }

  public void onStart(ScenarioContext ctx) {
    engine.onStart(ctx);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    engine.onTick(ctx, currentIntensity);
  }

  public void onReset(ScenarioContext ctx) {
    engine.reset();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "pipesCreated", engine.pipesCreated(),
        "livePipes", engine.livePipes(),
        "codeCacheUsedPct", engine.codeCacheUsedPct());
  }
}
