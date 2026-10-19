package com.jaframart.scenario;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseMachineTest {
  @Test
  void phasesIntensityStopResetAndConflict() {
    FakeScenario scenario = new FakeScenario();
    SafetyGuard guard = new SafetyGuard(() -> new SafetyGuard.Snapshot(0, 100, 0, 100, 0, 100, 0, 100, 1, null, null));
    PhaseMachine machine = new PhaseMachine("catalog-search", scenario, guard, 20261019L);
    Instant t0 = Instant.parse("2026-10-19T12:00:00Z");

    ScenarioStatus started = machine.start(new StartRequest(1.0, 10, 30, 10, 42L, "run-1"), t0);
    assertEquals(ScenarioState.RAMPING, started.state());
    assertEquals(0.0, started.currentIntensity(), 0.0001);
    assertEquals(42L, started.seed());
    assertEquals(1, scenario.starts);
    assertThrows(ScenarioRegistry.ControlException.class, () -> machine.start(StartRequest.empty(), t0));

    ScenarioStatus midRamp = machine.tick(t0.plusSeconds(5));
    assertEquals(ScenarioState.RAMPING, midRamp.state());
    assertEquals(0.5, midRamp.currentIntensity(), 0.0001);

    machine.stop(t0.plusSeconds(5));
    ScenarioStatus stopped = machine.status(t0.plusSeconds(5));
    assertEquals(ScenarioState.RECOVERING, stopped.state());
    assertEquals(0.5, stopped.currentIntensity(), 0.0001);

    ScenarioStatus idle = machine.tick(t0.plusSeconds(15));
    assertEquals(ScenarioState.IDLE, idle.state());
    assertEquals(1, scenario.resets);
    machine.stop(t0.plusSeconds(16));
    assertEquals(ScenarioState.IDLE, machine.status(t0.plusSeconds(16)).state());
  }

  @Test
  void fullCycleAndTickErrors() {
    FakeScenario scenario = new FakeScenario();
    SafetyGuard guard = new SafetyGuard(() -> new SafetyGuard.Snapshot(0, 100, 0, 100, 0, 100, 0, 100, 1, null, null));
    PhaseMachine machine = new PhaseMachine("catalog-search", scenario, guard, 20261019L);
    Instant t0 = Instant.parse("2026-10-19T12:00:00Z");
    machine.start(new StartRequest(1.0, 10, 30, 10, null, null), t0);
    assertEquals(Seeds.derive(20261019L, "S01"), machine.status(t0).seed());

    ScenarioState previous = ScenarioState.RAMPING;
    for (int second = 1; second <= 50; second++) {
      if (second == 12) {
        scenario.explode = true;
      }
      ScenarioStatus status = machine.tick(t0.plusSeconds(second));
      if (second < 10) {
        assertEquals(ScenarioState.RAMPING, status.state());
      } else if (second < 40) {
        assertEquals(ScenarioState.HOLDING, status.state());
      } else if (second < 50) {
        assertEquals(ScenarioState.RECOVERING, status.state());
      } else {
        assertEquals(ScenarioState.IDLE, status.state());
      }
      assertTrue(status.state() == previous || advanced(previous, status.state()));
      previous = status.state();
    }
    assertTrue(((Number) machine.status(t0.plusSeconds(50)).metrics().get("tickErrors")).intValue() >= 1);
    int resets = scenario.resets;
    machine.reset(t0.plusSeconds(51));
    assertEquals(resets + 1, scenario.resets);
    assertEquals(ScenarioState.IDLE, machine.status(t0.plusSeconds(51)).state());
  }

  @Test
  void rejectsBadIntensityAndSeconds() {
    FakeScenario scenario = new FakeScenario();
    SafetyGuard guard = new SafetyGuard(() -> new SafetyGuard.Snapshot(0, 100, 0, 100, 0, 100, 0, 100, 1, null, null));
    PhaseMachine machine = new PhaseMachine("catalog-search", scenario, guard, 1L);
    Instant now = Instant.EPOCH;
    ScenarioRegistry.ControlException intensity = assertThrows(ScenarioRegistry.ControlException.class,
        () -> machine.start(new StartRequest(2.0, null, null, null, null, null), now));
    assertEquals(ScenarioRegistry.ControlException.Kind.BAD_REQUEST, intensity.kind());
    ScenarioRegistry.ControlException seconds = assertThrows(ScenarioRegistry.ControlException.class,
        () -> machine.start(new StartRequest(1.0, 0, null, null, null, null), now));
    assertEquals(ScenarioRegistry.ControlException.Kind.BAD_REQUEST, seconds.kind());
  }

  @Test
  void registryUnknownIdAndResetAll() {
    ScenarioRegistry registry = new ScenarioRegistry("catalog-search", List.of(new FakeScenario()), 20261019L, false);
    assertEquals(1, registry.list().size());
    ScenarioRegistry.ControlException missing = assertThrows(ScenarioRegistry.ControlException.class,
        () -> registry.get("S99"));
    assertEquals(ScenarioRegistry.ControlException.Kind.NOT_FOUND, missing.kind());
    registry.start("S01", StartRequest.empty());
    assertEquals(ScenarioState.RAMPING, registry.get("S01").state());
    assertEquals(ScenarioState.IDLE, registry.resetAll().get(0).state());
    registry.close();
  }

  private static boolean advanced(ScenarioState from, ScenarioState to) {
    return (from == ScenarioState.RAMPING && to == ScenarioState.HOLDING)
        || (from == ScenarioState.HOLDING && to == ScenarioState.RECOVERING)
        || (from == ScenarioState.RECOVERING && to == ScenarioState.IDLE);
  }

  static final class FakeScenario implements Scenario {
    int starts;
    int resets;
    boolean explode;
    final AtomicInteger ticks = new AtomicInteger();

    public String id() { return "S01"; }
    public String name() { return "cpu-flash-sale"; }
    public List<Integer> subsystems() { return List.of(1); }
    public int defaultRampSeconds() { return 10; }
    public int defaultHoldSeconds() { return 30; }
    public int defaultRecoverSeconds() { return 10; }
    public Map<String, Object> params() { return Map.of("fuzzyExpansions", 1); }
    public void onStart(ScenarioContext ctx) { starts++; }
    public void onTick(ScenarioContext ctx, double currentIntensity) {
      ticks.incrementAndGet();
      if (explode) {
        explode = false;
        throw new IllegalStateException("boom");
      }
    }
    public void onReset(ScenarioContext ctx) { resets++; }
    public Map<String, Object> metrics() { return Map.of("fuzzySearches", ticks.get()); }
  }
}
