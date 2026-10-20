package com.jaframart.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaframart.scenario.PodIdentity;
import com.jaframart.scenario.StartRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.Executors;

final class ApiServer {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final Conductor conductor;
  private final LoadEngine load;
  private final GroundTruthWriter groundTruth;
  private HttpServer server;

  ApiServer(Conductor conductor, LoadEngine load, GroundTruthWriter groundTruth) {
    this.conductor = conductor;
    this.load = load;
    this.groundTruth = groundTruth;
  }

  void start(int port) throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/api/health", this::health);
    server.createContext("/api/info", this::info);
    server.createContext("/api/scenarios", this::scenarios);
    server.createContext("/api/timeline", this::timeline);
    server.createContext("/api/runs", this::runs);
    server.createContext("/api/ground-truth", this::groundTruth);
    server.createContext("/api/load", this::loadApi);
    server.start();
  }

  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private void health(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      send(ex, 405, Map.of("error", "METHOD_NOT_ALLOWED"));
      return;
    }
    int loggedIn = load.customersLoggedIn();
    int code = loggedIn >= 200 ? 200 : 503;
    send(ex, code, Map.of(
        "status", loggedIn >= 200 ? "UP" : "STARTING",
        "customersLoggedIn", loggedIn,
        "baseline", load.baselineRunning()));
  }

  private void info(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      send(ex, 405, Map.of("error", "METHOD_NOT_ALLOWED"));
      return;
    }
    send(ex, 200, Map.of(
        "component", "loadgen",
        "framework", "plain-java",
        "gc", gc(),
        "java", System.getProperty("java.version"),
        "pod", PodIdentity.pod(),
        "startedAt", ISO.format(STARTED)));
  }

  private void scenarios(HttpExchange ex) throws IOException {
    try {
      String path = ex.getRequestURI().getPath();
      String method = ex.getRequestMethod();
      if ("GET".equals(method) && "/api/scenarios".equals(path)) {
        send(ex, 200, conductor.scenariosView());
        return;
      }
      if ("POST".equals(method) && "/api/scenarios/reset-all".equals(path)) {
        send(ex, 200, conductor.resetAll());
        return;
      }
      if (path.startsWith("/api/scenarios/") && path.endsWith("/start") && "POST".equals(method)) {
        String id = path.substring("/api/scenarios/".length(), path.length() - "/start".length());
        StartRequest req = readStart(ex.getRequestBody());
        send(ex, 202, conductor.startManual(id, req));
        return;
      }
      if (path.startsWith("/api/scenarios/") && path.endsWith("/stop") && "POST".equals(method)) {
        String id = path.substring("/api/scenarios/".length(), path.length() - "/stop".length());
        send(ex, 202, conductor.stopScenario(id));
        return;
      }
      if (path.startsWith("/api/scenarios/") && path.endsWith("/reset") && "POST".equals(method)) {
        String id = path.substring("/api/scenarios/".length(), path.length() - "/reset".length());
        send(ex, 200, conductor.resetScenario(id));
        return;
      }
      send(ex, 404, Map.of("error", "NOT_FOUND"));
    } catch (Conductor.NotFound e) {
      send(ex, 404, Map.of("error", "NOT_FOUND", "message", e.getMessage()));
    } catch (Conductor.BadRequest e) {
      send(ex, 400, Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    } catch (Conductor.Conflict e) {
      send(ex, 409, Map.of("error", "CONFLICT", "message", e.getMessage()));
    } catch (Exception e) {
      send(ex, 500, Map.of("error", "INTERNAL", "message", e.getMessage()));
    }
  }

  private void timeline(HttpExchange ex) throws IOException {
    try {
      if (!"GET".equals(ex.getRequestMethod())) {
        send(ex, 405, Map.of("error", "METHOD_NOT_ALLOWED"));
        return;
      }
      String script = query(ex.getRequestURI(), "script");
      send(ex, 200, conductor.timeline(script));
    } catch (Conductor.NotFound e) {
      send(ex, 404, Map.of("error", "NOT_FOUND", "message", e.getMessage()));
    }
  }

  private void runs(HttpExchange ex) throws IOException {
    try {
      String path = ex.getRequestURI().getPath();
      String method = ex.getRequestMethod();
      if ("GET".equals(method) && "/api/runs".equals(path)) {
        send(ex, 200, conductor.listRuns());
        return;
      }
      if ("POST".equals(method) && "/api/runs".equals(path)) {
        JsonNode body = readJson(ex.getRequestBody());
        String script = text(body, "script");
        Long seed = body.hasNonNull("seed") ? body.get("seed").asLong() : null;
        Double ts = body.hasNonNull("timeScale") ? body.get("timeScale").asDouble() : null;
        send(ex, 201, conductor.startRun(script, seed, ts));
        return;
      }
      if ("GET".equals(method) && "/api/runs/current".equals(path)) {
        send(ex, 200, conductor.currentRun());
        return;
      }
      if ("POST".equals(method) && "/api/runs/current/stop".equals(path)) {
        send(ex, 200, conductor.stopRun());
        return;
      }
      send(ex, 404, Map.of("error", "NOT_FOUND"));
    } catch (Conductor.NotFound e) {
      send(ex, 404, Map.of("error", "NOT_FOUND", "message", e.getMessage()));
    } catch (Conductor.BadRequest e) {
      send(ex, 400, Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    } catch (Conductor.Conflict e) {
      send(ex, 409, Map.of("error", "CONFLICT", "message", e.getMessage()));
    } catch (Exception e) {
      send(ex, 500, Map.of("error", "INTERNAL", "message", e.getMessage()));
    }
  }

  private void groundTruth(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      send(ex, 405, Map.of("error", "METHOD_NOT_ALLOWED"));
      return;
    }
    String runId = query(ex.getRequestURI(), "runId");
    if (runId == null || runId.isBlank()) {
      send(ex, 400, Map.of("error", "BAD_REQUEST", "message", "runId required"));
      return;
    }
    byte[] body = groundTruth.readFile(runId);
    if (body == null) {
      send(ex, 404, Map.of("error", "NOT_FOUND"));
      return;
    }
    ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(body);
    }
  }

  private void loadApi(HttpExchange ex) throws IOException {
    try {
      String path = ex.getRequestURI().getPath();
      String method = ex.getRequestMethod();
      if ("GET".equals(method) && "/api/load".equals(path)) {
        send(ex, 200, load.loadSnapshot());
        return;
      }
      if ("PUT".equals(method) && "/api/load".equals(path)) {
        JsonNode body = readJson(ex.getRequestBody());
        double jps = body.path("baseJps").asDouble(12);
        load.setBaseJps(jps);
        send(ex, 200, load.loadSnapshot());
        return;
      }
      if ("GET".equals(method) && path.startsWith("/api/load/latency")) {
        String step = query(ex.getRequestURI(), "step");
        String window = query(ex.getRequestURI(), "window");
        if (step == null || step.isBlank()) {
          send(ex, 400, Map.of("error", "BAD_REQUEST", "message", "step required"));
          return;
        }
        int w = window == null ? 60 : Integer.parseInt(window);
        send(ex, 200, load.latency(step, w));
        return;
      }
      send(ex, 404, Map.of("error", "NOT_FOUND"));
    } catch (IllegalArgumentException e) {
      send(ex, 400, Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    } catch (Exception e) {
      send(ex, 500, Map.of("error", "INTERNAL", "message", e.getMessage()));
    }
  }

  private static StartRequest readStart(InputStream in) throws IOException {
    JsonNode body = readJson(in);
    if (body == null || body.isNull() || body.isMissingNode()) {
      return StartRequest.empty();
    }
    Double intensity = body.hasNonNull("intensity") ? body.get("intensity").asDouble() : null;
    Integer ramp = body.hasNonNull("rampSeconds") ? body.get("rampSeconds").asInt() : null;
    Integer hold = body.hasNonNull("holdSeconds") ? body.get("holdSeconds").asInt() : null;
    Integer recover = body.hasNonNull("recoverSeconds") ? body.get("recoverSeconds").asInt() : null;
    Long seed = body.hasNonNull("seed") ? body.get("seed").asLong() : null;
    String runId = text(body, "runId");
    return new StartRequest(intensity, ramp, hold, recover, seed, runId);
  }

  private static JsonNode readJson(InputStream in) throws IOException {
    byte[] bytes = in.readAllBytes();
    if (bytes.length == 0) {
      return Json.MAPPER.nullNode();
    }
    return Json.MAPPER.readTree(bytes);
  }

  private static String text(JsonNode body, String field) {
    if (body == null || !body.hasNonNull(field)) {
      return null;
    }
    return body.get(field).asText();
  }

  private static String query(URI uri, String key) {
    String q = uri.getRawQuery();
    if (q == null) {
      return null;
    }
    for (String part : q.split("&")) {
      int eq = part.indexOf('=');
      String k = eq < 0 ? part : part.substring(0, eq);
      String v = eq < 0 ? "" : part.substring(eq + 1);
      if (key.equals(k)) {
        return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static void send(HttpExchange ex, int status, Object body) throws IOException {
    byte[] bytes = Json.bytes(body);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String gc() {
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = bean.getName();
      if (name.startsWith("G1")) {
        return "G1";
      }
    }
    return "unknown";
  }
}
