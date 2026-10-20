package com.jaframart.cart.scenario;

import com.jaframart.cart.coupon.CouponValidator;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class ExceptionsCouponStorm implements Scenario {
  private final CouponValidator validator;

  public ExceptionsCouponStorm(CouponValidator validator) {
    this.validator = validator;
  }

  public String id() { return "S07"; }
  public String name() { return "exceptions-coupon-storm"; }
  public List<Integer> subsystems() { return List.of(16); }
  public int defaultRampSeconds() { return 30; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 30; }

  public Map<String, Object> params() {
    return Map.of("storm", true);
  }

  public void onStart(ScenarioContext ctx) {
    validator.resetCounters();
    validator.setStorm(false);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    validator.setStorm(currentIntensity > 0);
  }

  public void onReset(ScenarioContext ctx) {
    validator.setStorm(false);
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "exceptionsThrown", validator.exceptionsThrown(),
        "couponChecks", validator.couponChecks());
  }
}
