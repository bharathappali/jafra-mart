package com.jaframart.inventory.scenario;

import com.jaframart.inventory.stock.StockLedger;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class LockHotSku implements Scenario {
  private final StockLedger ledger;

  public LockHotSku(StockLedger ledger) {
    this.ledger = ledger;
  }

  public String id() { return "S02"; }
  public String name() { return "lock-hot-sku"; }
  public List<Integer> subsystems() { return List.of(8); }
  public int defaultRampSeconds() { return 30; }
  public int defaultHoldSeconds() { return 210; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    return Map.of("hotSku", "SKU-00042");
  }

  public void onStart(ScenarioContext ctx) {
    ledger.resetMetrics();
    ledger.setScenarioIntensity(0);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    ledger.setScenarioIntensity(currentIntensity);
    if (currentIntensity > 0 && !ctx.guard().growthFrozen()) {
      ledger.fillHotWaitlist(ctx.seed(), currentIntensity);
    }
  }

  public void onReset(ScenarioContext ctx) {
    ledger.clearHotWaitlist();
    ledger.resetMetrics();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "hotSkuLockWaitMsP95", ledger.hotSkuLockWaitMsP95(),
        "hotSkuCalls", ledger.hotSkuCalls());
  }
}
