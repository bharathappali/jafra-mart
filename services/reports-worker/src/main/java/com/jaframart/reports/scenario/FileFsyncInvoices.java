package com.jaframart.reports.scenario;

import com.jaframart.reports.invoice.InvoiceWriter;
import com.jaframart.scenario.Scenario;
import com.jaframart.scenario.ScenarioContext;
import com.jaframart.scenario.Seeds;

import java.util.List;
import java.util.Map;

public final class FileFsyncInvoices implements Scenario {
  private final InvoiceWriter writer;

  public FileFsyncInvoices(InvoiceWriter writer) {
    this.writer = writer;
  }

  public String id() { return "S11"; }
  public String name() { return "file-fsync-invoices"; }
  public List<Integer> subsystems() { return List.of(15); }
  public int defaultRampSeconds() { return 30; }
  public int defaultHoldSeconds() { return 180; }
  public int defaultRecoverSeconds() { return 30; }

  public Map<String, Object> params() {
    return Map.of("chunkBytes", 256, "writers", 4);
  }

  public void onStart(ScenarioContext ctx) {
    writer.resetMetrics();
    writer.setDurableFraction(0, Seeds.derive(ctx.seed(), "S11"));
  }

  public void onTick(ScenarioContext ctx, double currentIntensity) {
    writer.setDurableFraction(currentIntensity, Seeds.derive(ctx.seed(), "S11"));
  }

  public void onReset(ScenarioContext ctx) {
    writer.setDurableFraction(0, Seeds.derive(ctx.seed(), "S11"));
    writer.resetMetrics();
  }

  public Map<String, Object> metrics() {
    return Map.of(
        "fsyncs", writer.fsyncs(),
        "fsyncMsP95", writer.fsyncMsP95(),
        "queueDepth", writer.queueDepth());
  }
}
