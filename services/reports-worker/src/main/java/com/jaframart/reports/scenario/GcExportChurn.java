package com.jaframart.reports.scenario;

import com.jaframart.reports.export.ExportService;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class GcExportChurn implements Scenario {
  private final ExportService exports;

  public GcExportChurn(ExportService exports) {
    this.exports = exports;
  }

  public String id() { return "S06"; }
  public String name() { return "gc-export-churn"; }
  public List<Integer> subsystems() { return List.of(3, 2); }
  public int defaultRampSeconds() { return 60; }
  public int defaultHoldSeconds() { return 120; }
  public int defaultRecoverSeconds() { return 60; }

  public Map<String, Object> params() {
    return Map.of("naiveCopies", 4, "exportSlots", 2);
  }

  public void onStart(ScenarioContext ctx) {
    exports.resetMetrics();
    exports.setNaiveIntensity(0);
    exports.applyGuard(false);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    exports.setNaiveIntensity(currentIntensity);
    exports.applyGuard(ctx.guard().growthFrozen());
  }

  public void onReset(ScenarioContext ctx) {
    exports.setNaiveIntensity(0);
    exports.applyGuard(false);
    exports.resetMetrics();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "exportsCompleted", exports.exportsCompleted(),
        "youngGcCount", exports.youngGcCount());
  }
}
