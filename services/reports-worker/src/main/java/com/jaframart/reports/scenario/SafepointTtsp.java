package com.jaframart.reports.scenario;

import com.jaframart.reports.reconcile.ReconciliationJob;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;

import java.util.List;
import java.util.Map;

public final class SafepointTtsp implements Scenario {
  private final ReconciliationJob job;

  public SafepointTtsp(ReconciliationJob job) {
    this.job = job;
  }

  public String id() { return "S13"; }
  public String name() { return "safepoint-ttsp"; }
  public List<Integer> subsystems() { return List.of(11); }
  public int defaultRampSeconds() { return 30; }
  public int defaultHoldSeconds() { return 120; }
  public int defaultRecoverSeconds() { return 30; }

  public Map<String, Object> params() {
    return Map.of("ledgerInts", 4_194_304, "allocMiBPerSec", 24);
  }

  public void onStart(ScenarioContext ctx) {
    job.resetMetrics();
    job.setIntensity(0);
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    job.setIntensity(currentIntensity);
  }

  public void onReset(ScenarioContext ctx) {
    job.setIntensity(0);
    job.resetMetrics();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "heartbeatStallsOver100ms", job.heartbeatStallsOver100ms(),
        "checksumCallMs", job.checksumCallMs());
  }
}
