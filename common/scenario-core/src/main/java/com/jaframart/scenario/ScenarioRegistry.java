package com.jaframart.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ScenarioRegistry implements AutoCloseable {
  private final Map<String, PhaseMachine> machines = new LinkedHashMap<>();
  private final SafetyGuard guard;
  private final ScheduledExecutorService clock;
  private final boolean manualClock;

  public ScenarioRegistry(String service, List<Scenario> scenarios) {
    this(service, scenarios, Seeds.globalSeed(), true, SafetyGuard.installed());
  }

  public ScenarioRegistry(String service, List<Scenario> scenarios, long globalSeed, boolean startClock) {
    this(service, scenarios, globalSeed, startClock, new SafetyGuard(zeroSampler()));
  }

  public ScenarioRegistry(String service, List<Scenario> scenarios, long globalSeed, boolean startClock, SafetyGuard guard) {
    this.guard = guard;
    this.manualClock = !startClock;
    for (Scenario scenario : scenarios) {
      machines.put(scenario.id(), new PhaseMachine(service, scenario, guard, globalSeed));
    }
    if (startClock) {
      clock = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "scenario-clock");
        thread.setDaemon(true);
        return thread;
      });
      clock.scheduleAtFixedRate(this::tickAll, 1000, 1000, TimeUnit.MILLISECONDS);
      clock.scheduleAtFixedRate(this::sampleGuard, 250, 250, TimeUnit.MILLISECONDS);
    } else {
      clock = null;
    }
  }

  public SafetyGuard guard() {
    return guard;
  }

  public List<ScenarioStatus> list() {
    Instant now = Instant.now();
    List<ScenarioStatus> statuses = new ArrayList<>();
    for (PhaseMachine machine : machines.values()) {
      statuses.add(machine.status(now));
    }
    return List.copyOf(statuses);
  }

  public ScenarioStatus get(String id) {
    return machine(id).status(Instant.now());
  }

  public ScenarioStatus start(String id, StartRequest request) {
    return machine(id).start(request, Instant.now());
  }

  public ScenarioStatus stop(String id) {
    return machine(id).stop(Instant.now());
  }

  public ScenarioStatus reset(String id) {
    return machine(id).reset(Instant.now());
  }

  public List<ScenarioStatus> resetAll() {
    Instant now = Instant.now();
    List<ScenarioStatus> statuses = new ArrayList<>();
    for (PhaseMachine machine : machines.values()) {
      statuses.add(machine.reset(now));
    }
    return List.copyOf(statuses);
  }

  public void tickForTests(Instant now) {
    if (!manualClock) {
      throw new IllegalStateException("tickForTests requires a manual clock");
    }
    for (PhaseMachine machine : machines.values()) {
      machine.tick(now);
    }
  }

  public void sampleGuardForTests(Instant now) {
    guard.sample(now);
  }

  @Override
  public void close() {
    if (clock != null) {
      clock.shutdownNow();
    }
  }

  private void tickAll() {
    Instant now = Instant.now();
    for (PhaseMachine machine : machines.values()) {
      machine.tick(now);
    }
  }

  private void sampleGuard() {
    guard.sample(Instant.now());
  }

  private PhaseMachine machine(String id) {
    PhaseMachine machine = machines.get(id);
    if (machine == null) {
      throw notFound(id);
    }
    return machine;
  }

  public static ControlException notFound(String id) {
    return new ControlException(ControlException.Kind.NOT_FOUND, "unknown scenario " + id);
  }

  public static ControlException badRequest(String message) {
    return new ControlException(ControlException.Kind.BAD_REQUEST, message);
  }

  public static ControlException conflict(String message) {
    return new ControlException(ControlException.Kind.CONFLICT, message);
  }

  private static SafetyGuard.Sampler zeroSampler() {
    return () -> new SafetyGuard.Snapshot(0, 1, 0, 1, 0, 1, 0, 1, 1, null, null);
  }

  public static final class ControlException extends RuntimeException {
    public enum Kind { NOT_FOUND, BAD_REQUEST, CONFLICT }

    private final Kind kind;

    public ControlException(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }
}
