package com.jaframart.pricing.scenario;

import com.jaframart.pricing.engine.CampaignEngine;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class MetaspaceCampaignClasses implements Scenario {
  private final CampaignEngine engine;

  public MetaspaceCampaignClasses(CampaignEngine engine) {
    this.engine = engine;
  }

  public String id() { return "S08"; }
  public String name() { return "metaspace-campaign-classes"; }
  public List<Integer> subsystems() { return List.of(4, 13); }
  public int defaultRampSeconds() { return 60; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    return Map.of("teardownGc", engine.teardownGc());
  }

  public void onStart(ScenarioContext ctx) {
    engine.setHoldSeconds(180);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    engine.onTick(ctx, currentIntensity);
  }

  public void onReset(ScenarioContext ctx) {
    engine.reset();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "hiddenClassesLive", engine.hiddenClassesLive(),
        "campaignsCreated", engine.campaignsCreated(),
        "metaspaceUsedMiB", engine.metaspaceUsedMiB());
  }
}
