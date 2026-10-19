package com.jaframart.scenario;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PhaseMachine {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private final String service;
  private final Scenario scenario;
  private final SafetyGuard guard;
  private final long globalSeed;
  private ScenarioContext context;
  private ScenarioState state = ScenarioState.IDLE;
  private double intensity = 1.0;
  private double recoverBase = 1.0;
  private int rampSeconds;
  private int holdSeconds;
  private int recoverSeconds;
  private long seed;
  private String runId;
  private Instant phaseStart = Instant.EPOCH;
  private Instant startedAt;
  private Instant holdStartedAt;
  private Instant recoverStartedAt;
  private Instant endedAt;
  private int tickErrors;

  public PhaseMachine(String service, Scenario scenario, SafetyGuard guard, long globalSeed) {
    this.service = service;
    this.scenario = scenario;
    this.guard = guard;
    this.globalSeed = globalSeed;
    this.rampSeconds = scenario.defaultRampSeconds();
    this.holdSeconds = scenario.defaultHoldSeconds();
    this.recoverSeconds = scenario.defaultRecoverSeconds();
    this.seed = Seeds.derive(globalSeed, scenario.id());
    this.context = newContext(this.seed);
  }

  public synchronized ScenarioStatus start(StartRequest request, Instant now) {
    StartRequest req = request == null ? StartRequest.empty() : request;
    if (state != ScenarioState.IDLE) {
      throw ScenarioRegistry.conflict(scenario.id() + " is " + state);
    }
    intensity = req.intensity() == null ? 1.0 : req.intensity();
    if (intensity < 0.1 || intensity > 1.0) {
      throw ScenarioRegistry.badRequest("intensity must be between 0.1 and 1.0");
    }
    rampSeconds = seconds(req.rampSeconds(), scenario.defaultRampSeconds(), "rampSeconds");
    holdSeconds = seconds(req.holdSeconds(), scenario.defaultHoldSeconds(), "holdSeconds");
    recoverSeconds = seconds(req.recoverSeconds(), scenario.defaultRecoverSeconds(), "recoverSeconds");
    seed = req.seed() == null ? Seeds.derive(globalSeed, scenario.id()) : req.seed();
    runId = req.runId();
    context = newContext(seed);
    state = ScenarioState.RAMPING;
    phaseStart = now;
    startedAt = now;
    holdStartedAt = null;
    recoverStartedAt = null;
    endedAt = null;
    tickErrors = 0;
    recoverBase = intensity;
    logPhase();
    scenario.onStart(context);
    return status(now);
  }

  public synchronized ScenarioStatus tick(Instant now) {
    if (state != ScenarioState.IDLE) {
      advance(now);
    }
    if (state != ScenarioState.IDLE) {
      try {
        scenario.onTick(context, currentIntensity(now));
      } catch (Exception ex) {
        tickErrors++;
        context.log("tick error " + ex.getMessage());
      }
    }
    return status(now);
  }

  public synchronized ScenarioStatus stop(Instant now) {
    if (state == ScenarioState.RAMPING || state == ScenarioState.HOLDING) {
      enterRecovering(now, currentIntensity(now));
    }
    return status(now);
  }

  public synchronized ScenarioStatus reset(Instant now) {
    boolean active = state != ScenarioState.IDLE;
    scenario.onReset(context);
    state = ScenarioState.IDLE;
    if (active) {
      endedAt = now;
    }
    return status(now);
  }

  public synchronized ScenarioStatus status(Instant now) {
    Map<String, Object> metrics = new LinkedHashMap<>(scenario.metrics());
    metrics.put("tickErrors", tickErrors);
    return new ScenarioStatus(
        scenario.id(),
        scenario.name(),
        service,
        PodIdentity.pod(),
        PodIdentity.container(),
        scenario.subsystems(),
        state,
        intensity,
        currentIntensity(now),
        rampSeconds,
        holdSeconds,
        recoverSeconds,
        seed,
        runId,
        iso(startedAt),
        iso(holdStartedAt),
        iso(recoverStartedAt),
        iso(endedAt),
        new LinkedHashMap<>(scenario.params()),
        metrics,
        guard.status());
  }

  private void advance(Instant now) {
    double elapsed = elapsedSeconds(now);
    if (state == ScenarioState.RAMPING && elapsed >= rampSeconds) {
      state = ScenarioState.HOLDING;
      phaseStart = now;
      holdStartedAt = now;
      logPhase();
      return;
    }
    if (state == ScenarioState.HOLDING && elapsed >= holdSeconds) {
      enterRecovering(now, intensity);
      return;
    }
    if (state == ScenarioState.RECOVERING && elapsed >= recoverSeconds) {
      scenario.onReset(context);
      state = ScenarioState.IDLE;
      endedAt = now;
      logPhase();
    }
  }

  private void enterRecovering(Instant now, double fromIntensity) {
    recoverBase = fromIntensity;
    state = ScenarioState.RECOVERING;
    phaseStart = now;
    recoverStartedAt = now;
    logPhase();
  }

  private double currentIntensity(Instant now) {
    double value = switch (state) {
      case IDLE -> 0.0;
      case RAMPING -> intensity * elapsedSeconds(now) / rampSeconds;
      case HOLDING -> intensity;
      case RECOVERING -> recoverBase * (1.0 - elapsedSeconds(now) / recoverSeconds);
    };
    if (value < 0) {
      return 0;
    }
    return Math.min(value, intensity);
  }

  private double elapsedSeconds(Instant now) {
    return Duration.between(phaseStart, now).toMillis() / 1000.0;
  }

  private ScenarioContext newContext(long scenarioSeed) {
    return new ScenarioContext(scenarioSeed, guard, message ->
        System.getLogger(PhaseMachine.class.getName()).log(System.Logger.Level.INFO, message));
  }

  private void logPhase() {
    context.log("SCENARIO " + scenario.id() + " " + state + " intensity=" + intensity + " seed=" + seed);
  }

  private static int seconds(Integer requested, int fallback, String name) {
    int value = requested == null ? fallback : requested;
    if (value < 1) {
      throw ScenarioRegistry.badRequest(name + " must be >= 1");
    }
    return value;
  }

  private static String iso(Instant instant) {
    return instant == null ? null : ISO.format(instant);
  }
}
