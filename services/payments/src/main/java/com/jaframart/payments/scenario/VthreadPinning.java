package com.jaframart.payments.scenario;

import com.jaframart.payments.gateway.PaymentService;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class VthreadPinning implements Scenario {
  private final PaymentService payments;

  public VthreadPinning(PaymentService payments) {
    this.payments = payments;
  }

  public String id() { return "S12"; }
  public String name() { return "vthread-pinning"; }
  public List<Integer> subsystems() { return List.of(10); }
  public int defaultRampSeconds() { return 30; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 30; }

  public Map<String, Object> params() {
    return Map.of("pinFraction", 0.8, "blockMs", 40);
  }

  public void onStart(ScenarioContext ctx) {
    payments.setPinIntensity(0, false);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    payments.setPinIntensity(currentIntensity, ctx.guard().growthFrozen());
  }

  public void onReset(ScenarioContext ctx) {
    payments.setPinIntensity(0, false);
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "pinnedScoringCalls", payments.pinnedScoringCalls(),
        "authorizeMsP95", payments.authorizeMsP95());
  }
}
