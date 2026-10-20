package com.jaframart.orders.scenario;

import com.jaframart.orders.notify.FulfillmentNotifier;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class ThreadsBurstPools implements Scenario {
  private final FulfillmentNotifier notifier;

  public ThreadsBurstPools(FulfillmentNotifier notifier) {
    this.notifier = notifier;
  }

  public String id() { return "S04"; }
  public String name() { return "threads-burst-pools"; }
  public List<Integer> subsystems() { return List.of(9); }
  public int defaultRampSeconds() { return 60; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    return Map.of(
        "burstOrders", 20,
        "burstWindowMs", 2_000,
        "detectCooldownMs", 5_000,
        "maxPools", 40,
        "threadsPerPool", 8);
  }

  public void onStart(ScenarioContext ctx) {
    notifier.resetToBaseline();
    notifier.setGrowthFrozen(false);
    notifier.setScenarioIntensity(0);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    notifier.setGrowthFrozen(ctx.guard().growthFrozen());
    notifier.setScenarioIntensity(currentIntensity);
  }

  public void onReset(ScenarioContext ctx) {
    notifier.resetToBaseline();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "leakedPools", notifier.leakedPools(),
        "threadCount", notifier.threadCount());
  }
}
