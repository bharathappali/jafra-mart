package com.jaframart.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaframart.scenario.ScenarioState;
import com.jaframart.scenario.Seeds;
import com.jaframart.scenario.StartRequest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class Conductor {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Set<Double> TIME_SCALES = Set.of(1.0, 0.5, 0.1);

  private final ScenarioCatalog catalog;
  private final ScriptDef script;
  private final LoadEngine load;
  private final GroundTruthWriter groundTruth;
  private final Map<String, String> serviceUrls;
  private final HttpClients.Session direct;
  private final long defaultSeed;
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "conductor-clock");
    t.setDaemon(true);
    t.setPriority(Thread.MAX_PRIORITY);
    return t;
  });
  private final ScheduledExecutorService polls = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "conductor-poll");
    t.setDaemon(true);
    return t;
  });

  private final Map<String, TrackedScenario> tracked = new ConcurrentHashMap<>();
  private final List<Map<String, Object>> runHistory = new ArrayList<>();
  private final AtomicReference<RunState> current = new AtomicReference<>();

  Conductor(ScenarioCatalog catalog, ScriptDef script, LoadEngine load, GroundTruthWriter groundTruth,
            Map<String, String> serviceUrls, HttpClients httpClients, long defaultSeed) {
    this.catalog = catalog;
    this.script = script;
    this.load = load;
    this.groundTruth = groundTruth;
    this.serviceUrls = serviceUrls;
    this.direct = httpClients.session();
    this.defaultSeed = defaultSeed;
    for (ScenarioCatalog.Entry entry : catalog.all()) {
      tracked.put(entry.id(), new TrackedScenario(entry));
    }
    polls.scheduleAtFixedRate(this::pollStatuses, 200, 200, TimeUnit.MILLISECONDS);
    clock.scheduleAtFixedRate(this::advanceRun, 200, 200, TimeUnit.MILLISECONDS);
  }

  List<Map<String, Object>> scenariosView() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ScriptDef.Entry planned : script.entries()) {
      ScenarioCatalog.Entry meta = catalog.get(planned.id());
      TrackedScenario track = tracked.get(planned.id());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", meta.id());
      row.put("name", meta.name());
      row.put("title", meta.title());
      row.put("service", meta.service());
      row.put("subsystems", meta.subsystems());
      Map<String, Object> plan = new LinkedHashMap<>();
      plan.put("startAt", formatMmSs(planned.startAt()));
      plan.put("ramp", planned.ramp());
      plan.put("hold", planned.hold());
      plan.put("recover", planned.recover());
      row.put("planned", plan);
      row.put("status", track == null ? null : track.lastStatus);
      out.add(row);
    }
    // include any catalog entry missing from script (should be none)
    for (ScenarioCatalog.Entry meta : catalog.all()) {
      boolean found = false;
      for (Map<String, Object> row : out) {
        if (meta.id().equals(row.get("id"))) {
          found = true;
          break;
        }
      }
      if (!found) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", meta.id());
        row.put("name", meta.name());
        row.put("title", meta.title());
        row.put("service", meta.service());
        row.put("subsystems", meta.subsystems());
        row.put("planned", Map.of());
        TrackedScenario track = tracked.get(meta.id());
        row.put("status", track == null ? null : track.lastStatus);
        out.add(row);
      }
    }
    return out;
  }

  Map<String, Object> timeline(String scriptName) {
    if (scriptName == null || scriptName.isBlank()) {
      scriptName = "default-30m";
    }
    if (!"default-30m".equals(scriptName)) {
      throw new NotFound("unknown script " + scriptName);
    }
    List<Map<String, Object>> entries = new ArrayList<>();
    for (ScriptDef.Entry planned : script.entries()) {
      ScenarioCatalog.Entry meta = catalog.get(planned.id());
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", planned.id());
      entry.put("name", meta.name());
      entry.put("title", meta.title());
      entry.put("service", meta.service());
      entry.put("subsystems", meta.subsystems());
      entry.put("startAt", planned.startAt());
      entry.put("ramp", planned.ramp());
      entry.put("hold", planned.hold());
      entry.put("recover", planned.recover());
      entry.put("intensity", planned.intensity());
      int rampStart = planned.startAt();
      int holdStart = rampStart + planned.ramp();
      int recoverStart = holdStart + planned.hold();
      int end = recoverStart + planned.recover();
      List<Map<String, Object>> phases = new ArrayList<>();
      phases.add(phasePlan("RAMPING", rampStart, holdStart));
      phases.add(phasePlan("HOLDING", holdStart, recoverStart));
      phases.add(phasePlan("RECOVERING", recoverStart, end));
      entry.put("planned", phases);
      entries.add(entry);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("script", "default-30m");
    body.put("durationSeconds", script.durationSeconds());
    body.put("entries", entries);
    return body;
  }

  Map<String, Object> startManual(String id, StartRequest request) {
    ScenarioCatalog.Entry meta = catalog.get(id);
    StartRequest req = request == null ? StartRequest.empty() : request;
    String runId = groundTruth.manualRunId();
    JsonNode status = forwardStart(meta, req, runId, "manual");
    TrackedScenario track = tracked.get(id);
    track.bind(runId, "manual", 0, req);
    track.recordInitialPhase(status, this);
    load.startScenarioStream(id, () -> currentIntensity(id));
    return statusToMap(status);
  }

  Map<String, Object> stopScenario(String id) {
    ScenarioCatalog.Entry meta = catalog.get(id);
    JsonNode status = forward(meta.service(), "/internal/scenarios/" + id + "/stop", "POST", null);
    return statusToMap(status);
  }

  Map<String, Object> resetScenario(String id) {
    ScenarioCatalog.Entry meta = catalog.get(id);
    load.stopScenarioStream(id);
    JsonNode status = forward(meta.service(), "/internal/scenarios/" + id + "/reset", "POST", null);
    TrackedScenario track = tracked.get(id);
    track.clearBinding();
    return statusToMap(status);
  }

  List<Map<String, Object>> resetAll() {
    load.stopAllScenarioStreams();
    List<Map<String, Object>> out = new ArrayList<>();
    for (String service : uniqueServices()) {
      JsonNode node = forward(service, "/internal/scenarios/reset-all", "POST", null);
      if (node != null && node.isArray()) {
        for (JsonNode child : node) {
          out.add(statusToMap(child));
        }
      }
    }
    for (TrackedScenario track : tracked.values()) {
      track.clearBinding();
    }
    return out;
  }

  Map<String, Object> startRun(String scriptName, Long seed, Double timeScale) {
    if (scriptName == null || scriptName.isBlank()) {
      scriptName = "default-30m";
    }
    if (!"default-30m".equals(scriptName)) {
      throw new BadRequest("unknown script " + scriptName);
    }
    double ts = timeScale == null ? 1.0 : timeScale;
    if (!TIME_SCALES.contains(ts)) {
      throw new BadRequest("timeScale must be 1.0, 0.5, or 0.1");
    }
    RunState existing = current.get();
    if (existing != null && "RUNNING".equals(existing.state)) {
      throw new Conflict("run already active");
    }
    long useSeed = seed == null ? defaultSeed : seed;
    Instant startedAt = Instant.now();
    String runId = "run-" + RUN_ID.format(startedAt);
    Instant plannedEnd = startedAt.plusSeconds(Math.round(script.durationSeconds() * ts));

    writeRunEvent(runId, "run_started", startedAt, 0, useSeed, ts, null);
    resetAllServicesForRun(runId, startedAt, useSeed, ts);
    writeRunEvent(runId, "reset_all", Instant.now(), elapsed(startedAt), useSeed, ts, null);

    RunState run = new RunState(runId, "RUNNING", startedAt, plannedEnd, null, useSeed, ts, scriptName);
    current.set(run);
    synchronized (runHistory) {
      Map<String, Object> hist = new LinkedHashMap<>();
      hist.put("runId", runId);
      hist.put("state", "RUNNING");
      hist.put("startedAt", ISO.format(startedAt));
      hist.put("endedAt", null);
      runHistory.add(hist);
    }
    for (ScriptDef.Entry entry : script.entries()) {
      TrackedScenario track = tracked.get(entry.id());
      track.scheduleForRun(runId, entry, ts);
    }
    return Map.of(
        "runId", runId,
        "startedAt", ISO.format(startedAt),
        "plannedEndAt", ISO.format(plannedEnd),
        "timeScale", ts);
  }

  Map<String, Object> currentRun() {
    RunState run = current.get();
    if (run == null) {
      throw new NotFound("no run");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("runId", run.runId);
    body.put("state", run.state);
    body.put("elapsedSeconds", elapsed(run.startedAt));
    body.put("timeScale", run.timeScale);
    body.put("startedAt", ISO.format(run.startedAt));
    if (run.endedAt != null) {
      body.put("endedAt", ISO.format(run.endedAt));
    }
    return body;
  }

  Map<String, Object> stopRun() {
    RunState run = current.get();
    if (run == null || !"RUNNING".equals(run.state)) {
      throw new NotFound("no active run");
    }
    resetAll();
    Instant ended = Instant.now();
    run.state = "STOPPED";
    run.endedAt = ended;
    writeRunEvent(run.runId, "run_stopped", ended, elapsed(run.startedAt), run.seed, run.timeScale, null);
    updateHistory(run);
    return currentRun();
  }

  List<Map<String, Object>> listRuns() {
    synchronized (runHistory) {
      return List.copyOf(runHistory);
    }
  }

  private void advanceRun() {
    RunState run = current.get();
    if (run == null || !"RUNNING".equals(run.state)) {
      return;
    }
    double elapsed = elapsed(run.startedAt);
    for (ScriptDef.Entry entry : script.entries()) {
      TrackedScenario track = tracked.get(entry.id());
      if (track == null || track.runStarted || !run.runId.equals(track.runId)) {
        continue;
      }
      double startAt = entry.startAt() * run.timeScale;
      if (elapsed + 0.05 >= startAt) {
        startScripted(track, entry, run);
      }
    }
    if (elapsed >= script.durationSeconds() * run.timeScale) {
      completeRun(run);
    }
  }

  private void startScripted(TrackedScenario track, ScriptDef.Entry entry, RunState run) {
    ScenarioCatalog.Entry meta = catalog.get(entry.id());
    int ramp = scaledSeconds(entry.ramp(), run.timeScale);
    int hold = scaledSeconds(entry.hold(), run.timeScale);
    int recover = scaledSeconds(entry.recover(), run.timeScale);
    StartRequest req = new StartRequest(entry.intensity(), ramp, hold, recover, Seeds.derive(run.seed, entry.id()), run.runId);
    try {
      JsonNode status = forwardStart(meta, req, run.runId, "script");
      track.bind(run.runId, "script", entry.startAt(), req);
      track.recordInitialPhase(status, this);
      track.runStarted = true;
      load.startScenarioStream(entry.id(), () -> currentIntensity(entry.id()));
    } catch (Exception ex) {
      System.err.println("failed to start " + entry.id() + ": " + ex.getMessage());
    }
  }

  private void completeRun(RunState run) {
    load.stopAllScenarioStreams();
    // Wait for in-flight scenarios to reach IDLE so phase/summary lines precede run_completed.
    long waitDeadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < waitDeadline) {
      boolean allIdle = true;
      for (TrackedScenario track : tracked.values()) {
        if (track.lastStatus != null) {
          Object state = track.lastStatus.get("state");
          if (state != null && !"IDLE".equals(String.valueOf(state))) {
            allIdle = false;
            break;
          }
        }
      }
      if (allIdle) {
        break;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      }
      pollStatuses();
    }
    Instant ended = Instant.now();
    run.state = "COMPLETED";
    run.endedAt = ended;
    writeRunEvent(run.runId, "run_completed", ended, elapsed(run.startedAt), run.seed, run.timeScale, null);
    updateHistory(run);
  }

  private void pollStatuses() {
    for (ScenarioCatalog.Entry meta : catalog.all()) {
      try {
        JsonNode status = forward(meta.service(), "/internal/scenarios/" + meta.id(), "GET", null);
        if (status == null || status.isMissingNode()) {
          continue;
        }
        TrackedScenario track = tracked.get(meta.id());
        track.onStatus(status, this);
      } catch (Exception ignored) {
        // service may be briefly unavailable
      }
    }
  }

  void onPhase(TrackedScenario track, String phase, JsonNode status) {
    if (track.runId == null) {
      return;
    }
    Instant ts = phaseInstant(phase, status);
    if (ts == null) {
      ts = Instant.now();
    }
    RunState run = current.get();
    double offset = 0;
    if (run != null && "script".equals(track.source)) {
      offset = Duration.between(run.startedAt, ts).toMillis() / 1000.0;
    }
    double planned = plannedOffset(track, phase);
    LinkedHashMap<String, Object> fields = baseScenarioFields(track, status);
    fields.put("type", "phase");
    fields.put("phase", phase);
    fields.put("ts", ISO.format(ts));
    fields.put("tsEpochMs", ts.toEpochMilli());
    fields.put("offsetSeconds", round2(offset));
    fields.put("plannedOffsetSeconds", planned);
    fields.put("intensity", status.path("currentIntensity").asDouble(status.path("intensity").asDouble(0)));
    fields.put("seed", status.path("seed").asLong(0));
    fields.put("params", Json.MAPPER.convertValue(status.path("params"), Object.class));
    fields.put("source", track.source);
    fields.put("expected", Map.of(
        "primary", track.meta.expected().primary(),
        "secondary", track.meta.expected().secondary()));
    groundTruth.write(track.runId, fields);

    if ("IDLE".equals(phase)) {
      writeSummary(track, status);
      load.stopScenarioStream(track.meta.id());
    }
  }

  void onGuard(TrackedScenario track, JsonNode status, boolean tripped) {
    if (track.runId == null) {
      return;
    }
    Instant ts = Instant.now();
    LinkedHashMap<String, Object> fields = baseScenarioFields(track, status);
    fields.put("type", "guard");
    fields.put("phase", status.path("state").asText());
    fields.put("ts", ISO.format(ts));
    fields.put("tsEpochMs", ts.toEpochMilli());
    fields.put("offsetSeconds", current.get() != null ? round2(elapsed(current.get().startedAt)) : 0);
    fields.put("plannedOffsetSeconds", 0);
    fields.put("intensity", status.path("currentIntensity").asDouble(0));
    fields.put("seed", status.path("seed").asLong(0));
    fields.put("params", Map.of("tripped", tripped, "reason", status.path("guard").path("reason").asText(null)));
    fields.put("source", track.source == null ? "manual" : track.source);
    fields.put("expected", Map.of(
        "primary", track.meta.expected().primary(),
        "secondary", track.meta.expected().secondary()));
    groundTruth.write(track.runId, fields);
  }

  private void writeSummary(TrackedScenario track, JsonNode status) {
    LinkedHashMap<String, Object> fields = baseScenarioFields(track, status);
    fields.put("type", "scenario_summary");
    fields.put("phase", "IDLE");
    Instant ts = Instant.now();
    fields.put("ts", ISO.format(ts));
    fields.put("tsEpochMs", ts.toEpochMilli());
    fields.put("offsetSeconds", current.get() != null ? round2(elapsed(current.get().startedAt)) : 0);
    fields.put("plannedOffsetSeconds", track.plannedStartAt + track.plannedRamp + track.plannedHold + track.plannedRecover);
    fields.put("intensity", 0.0);
    fields.put("seed", status.path("seed").asLong(0));
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("startTs", status.path("startedAt").asText(null));
    params.put("holdTs", status.path("holdStartedAt").asText(null));
    params.put("recoverTs", status.path("recoverStartedAt").asText(null));
    params.put("endTs", status.path("endedAt").asText(null));
    params.put("metrics", Json.MAPPER.convertValue(status.path("metrics"), Object.class));
    fields.put("params", params);
    fields.put("source", track.source);
    fields.put("expected", Map.of(
        "primary", track.meta.expected().primary(),
        "secondary", track.meta.expected().secondary()));
    groundTruth.write(track.runId, fields);
  }

  private LinkedHashMap<String, Object> baseScenarioFields(TrackedScenario track, JsonNode status) {
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("scenarioId", track.meta.id());
    fields.put("scenarioName", track.meta.name());
    fields.put("service", track.meta.service());
    fields.put("namespace", "jafra-mart");
    fields.put("pod", status.path("pod").asText(""));
    fields.put("container", status.path("container").asText(track.meta.service()));
    fields.put("subsystems", track.meta.subsystems());
    fields.put("effects", track.meta.effects());
    return fields;
  }

  private double plannedOffset(TrackedScenario track, String phase) {
    return switch (phase) {
      case "RAMPING" -> track.plannedStartAt;
      case "HOLDING" -> track.plannedStartAt + track.plannedRamp;
      case "RECOVERING" -> track.plannedStartAt + track.plannedRamp + track.plannedHold;
      case "IDLE" -> track.plannedStartAt + track.plannedRamp + track.plannedHold + track.plannedRecover;
      default -> track.plannedStartAt;
    };
  }

  private Instant phaseInstant(String phase, JsonNode status) {
    String field = switch (phase) {
      case "RAMPING" -> "startedAt";
      case "HOLDING" -> "holdStartedAt";
      case "RECOVERING" -> "recoverStartedAt";
      case "IDLE" -> "endedAt";
      default -> null;
    };
    if (field == null) {
      return null;
    }
    String raw = status.path(field).asText(null);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (Exception ex) {
      return null;
    }
  }

  private JsonNode forwardStart(ScenarioCatalog.Entry meta, StartRequest req, String runId, String source) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (req.intensity() != null) {
      body.put("intensity", req.intensity());
    }
    if (req.rampSeconds() != null) {
      body.put("rampSeconds", req.rampSeconds());
    }
    if (req.holdSeconds() != null) {
      body.put("holdSeconds", req.holdSeconds());
    }
    if (req.recoverSeconds() != null) {
      body.put("recoverSeconds", req.recoverSeconds());
    }
    if (req.seed() != null) {
      body.put("seed", req.seed());
    }
    body.put("runId", runId);
    return forward(meta.service(), "/internal/scenarios/" + meta.id() + "/start", "POST", body);
  }

  private void resetAllServicesForRun(String runId, Instant startedAt, long seed, double ts) {
    for (String service : uniqueServices()) {
      forward(service, "/internal/scenarios/reset-all", "POST", null);
    }
  }

  private JsonNode forward(String service, String path, String method, Object body) {
    String base = serviceUrls.get(service);
    if (base == null) {
      throw new BadRequest("no url for service " + service);
    }
    try {
      HttpRequest.Builder b = HttpClients.request(URI.create(base + path));
      if ("GET".equals(method)) {
        b.GET();
      } else if (body == null) {
        b.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        b.header("Content-Type", "application/json");
        b.method(method, HttpRequest.BodyPublishers.ofString(Json.string(body)));
      }
      HttpResponse<byte[]> res = direct.send(b.build());
      if (res.statusCode() == 404) {
        throw new NotFound(new String(res.body(), StandardCharsets.UTF_8));
      }
      if (res.statusCode() == 409) {
        throw new Conflict(new String(res.body(), StandardCharsets.UTF_8));
      }
      if (res.statusCode() == 400) {
        throw new BadRequest(new String(res.body(), StandardCharsets.UTF_8));
      }
      if (res.statusCode() >= 300) {
        throw new IllegalStateException(service + " " + path + " -> " + res.statusCode());
      }
      if (res.body() == null || res.body().length == 0) {
        return Json.MAPPER.nullNode();
      }
      return Json.MAPPER.readTree(res.body());
    } catch (ConductorException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("forward " + service + path + ": " + ex.getMessage(), ex);
    }
  }

  private double currentIntensity(String id) {
    TrackedScenario track = tracked.get(id);
    if (track == null || track.lastStatus == null) {
      return 0;
    }
    Object ci = track.lastStatus.get("currentIntensity");
    if (ci instanceof Number n) {
      return n.doubleValue();
    }
    return 0;
  }

  private List<String> uniqueServices() {
    List<String> services = new ArrayList<>();
    for (ScenarioCatalog.Entry entry : catalog.all()) {
      if (!services.contains(entry.service())) {
        services.add(entry.service());
      }
    }
    return services;
  }

  private void writeRunEvent(String runId, String type, Instant ts, double offset, long seed, double tsScale,
                             Map<String, Object> extra) {
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("type", type);
    fields.put("scenarioId", null);
    fields.put("scenarioName", null);
    fields.put("service", "loadgen");
    fields.put("namespace", "jafra-mart");
    fields.put("pod", System.getenv().getOrDefault("POD_NAME", "loadgen"));
    fields.put("container", "loadgen");
    fields.put("subsystems", List.of());
    fields.put("effects", List.of());
    fields.put("phase", null);
    fields.put("ts", ISO.format(ts));
    fields.put("tsEpochMs", ts.toEpochMilli());
    fields.put("offsetSeconds", round2(offset));
    fields.put("plannedOffsetSeconds", type.equals("run_completed") ? 1800 : 0);
    fields.put("intensity", 0.0);
    fields.put("seed", seed);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("timeScale", tsScale);
    if (extra != null) {
      params.putAll(extra);
    }
    fields.put("params", params);
    fields.put("source", "script");
    fields.put("expected", Map.of("primary", List.of(), "secondary", List.of()));
    groundTruth.write(runId, fields);
  }

  private void updateHistory(RunState run) {
    synchronized (runHistory) {
      for (int i = 0; i < runHistory.size(); i++) {
        if (run.runId.equals(runHistory.get(i).get("runId"))) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("runId", run.runId);
          row.put("state", run.state);
          row.put("startedAt", ISO.format(run.startedAt));
          row.put("endedAt", run.endedAt == null ? null : ISO.format(run.endedAt));
          runHistory.set(i, row);
          return;
        }
      }
    }
  }

  private static int scaledSeconds(int seconds, double timeScale) {
    return Math.max(5, (int) Math.round(seconds * timeScale));
  }

  private static double elapsed(Instant startedAt) {
    return Duration.between(startedAt, Instant.now()).toMillis() / 1000.0;
  }

  private static double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
  }

  private static String formatMmSs(int seconds) {
    return String.format("%02d:%02d", seconds / 60, seconds % 60);
  }

  private static Map<String, Object> phasePlan(String phase, int start, int end) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("phase", phase);
    m.put("startSeconds", start);
    m.put("endSeconds", end);
    return m;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> statusToMap(JsonNode status) {
    if (status == null || status.isNull() || status.isMissingNode()) {
      return Map.of();
    }
    return Json.MAPPER.convertValue(status, Map.class);
  }

  static final class TrackedScenario {
    final ScenarioCatalog.Entry meta;
    volatile Map<String, Object> lastStatus;
    volatile String runId;
    volatile String source;
    volatile int plannedStartAt;
    volatile int plannedRamp;
    volatile int plannedHold;
    volatile int plannedRecover;
    volatile boolean runStarted;
    private String lastPhase;
    private Boolean lastGuard;

    TrackedScenario(ScenarioCatalog.Entry meta) {
      this.meta = meta;
    }

    void scheduleForRun(String runId, ScriptDef.Entry entry, double timeScale) {
      synchronized (this) {
        this.runId = runId;
        this.source = "script";
        this.plannedStartAt = entry.startAt();
        this.plannedRamp = entry.ramp();
        this.plannedHold = entry.hold();
        this.plannedRecover = entry.recover();
        this.runStarted = false;
        this.lastPhase = "IDLE";
        this.lastGuard = false;
      }
    }

    void bind(String runId, String source, int plannedStartAt, StartRequest req) {
      synchronized (this) {
        this.runId = runId;
        this.source = source;
        if (!"script".equals(source)) {
          this.plannedStartAt = plannedStartAt;
          this.plannedRamp = req.rampSeconds() == null ? 0 : req.rampSeconds();
          this.plannedHold = req.holdSeconds() == null ? 0 : req.holdSeconds();
          this.plannedRecover = req.recoverSeconds() == null ? 0 : req.recoverSeconds();
        }
        this.lastPhase = null;
        this.lastGuard = false;
      }
    }

    void recordInitialPhase(JsonNode status, Conductor conductor) {
      synchronized (this) {
        if (status == null || status.isNull()) {
          return;
        }
        lastStatus = statusToMap(status);
        String phase = status.path("state").asText("IDLE");
        if (java.util.Objects.equals(phase, lastPhase)) {
          return;
        }
        lastPhase = phase;
        if (!"IDLE".equals(phase)) {
          conductor.onPhase(this, phase, status);
        }
      }
    }

    void clearBinding() {
      this.runId = null;
      this.source = null;
      this.runStarted = false;
    }

    void onStatus(JsonNode status, Conductor conductor) {
      synchronized (this) {
        lastStatus = statusToMap(status);
        String phase = status.path("state").asText("IDLE");
        if (!Objects.equals(phase, lastPhase)) {
          String previous = lastPhase;
          lastPhase = phase;
          if (previous != null || !"IDLE".equals(phase)) {
            conductor.onPhase(this, phase, status);
          }
        }
        boolean tripped = status.path("guard").path("tripped").asBoolean(false);
        if (lastGuard == null || lastGuard != tripped) {
          if (lastGuard != null) {
            conductor.onGuard(this, status, tripped);
          }
          lastGuard = tripped;
        }
      }
    }
  }

  static final class RunState {
    final String runId;
    volatile String state;
    final Instant startedAt;
    final Instant plannedEnd;
    volatile Instant endedAt;
    final long seed;
    final double timeScale;
    final String script;

    RunState(String runId, String state, Instant startedAt, Instant plannedEnd, Instant endedAt,
             long seed, double timeScale, String script) {
      this.runId = runId;
      this.state = state;
      this.startedAt = startedAt;
      this.plannedEnd = plannedEnd;
      this.endedAt = endedAt;
      this.seed = seed;
      this.timeScale = timeScale;
      this.script = script;
    }
  }

  static class ConductorException extends RuntimeException {
    ConductorException(String message) {
      super(message);
    }
  }

  static final class NotFound extends ConductorException {
    NotFound(String message) {
      super(message);
    }
  }

  static final class BadRequest extends ConductorException {
    BadRequest(String message) {
      super(message);
    }
  }

  static final class Conflict extends ConductorException {
    Conflict(String message) {
      super(message);
    }
  }
}
