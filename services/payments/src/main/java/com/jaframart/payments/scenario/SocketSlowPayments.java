package com.jaframart.payments.scenario;

import com.jaframart.payments.gateway.PaymentService;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class SocketSlowPayments implements Scenario {
  private final PaymentService payments;

  public SocketSlowPayments(PaymentService payments) {
    this.payments = payments;
  }

  public String id() { return "S03"; }
  public String name() { return "socket-slow-payments"; }
  public List<Integer> subsystems() { return List.of(14); }
  public int defaultRampSeconds() { return 60; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    return Map.of("baseLatencyMs", 40);
  }

  public void onStart(ScenarioContext ctx) {
    payments.setSlowIntensity(0, false);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    payments.setSlowIntensity(currentIntensity, ctx.guard().growthFrozen());
  }

  public void onReset(ScenarioContext ctx) {
    payments.setSlowIntensity(0, false);
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "baseLatencyMs", payments.baseLatencyMs(),
        "authorizations", payments.authorizations());
  }
}
