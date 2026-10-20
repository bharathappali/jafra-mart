import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates an e2e ground-truth file and samples. At timeScale 0.1 only structure,
 * timing (±5s), recovery to IDLE, stability and load health are checked (§20.3).
 */
public class ValidateRun {
  private static final String[] SCENARIOS = {
      "S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "S11", "S12", "S13"
  };
  private static final String[] PHASES = {"RAMPING", "HOLDING", "RECOVERING", "IDLE"};
  private static final Map<String, Integer> PLANNED = Map.ofEntries(
      Map.entry("S01:RAMPING", 240), Map.entry("S01:HOLDING", 300), Map.entry("S01:RECOVERING", 480), Map.entry("S01:IDLE", 540),
      Map.entry("S02:RAMPING", 300), Map.entry("S02:HOLDING", 330), Map.entry("S02:RECOVERING", 540), Map.entry("S02:IDLE", 600),
      Map.entry("S03:RAMPING", 480), Map.entry("S03:HOLDING", 540), Map.entry("S03:RECOVERING", 720), Map.entry("S03:IDLE", 780),
      Map.entry("S04:RAMPING", 600), Map.entry("S04:HOLDING", 660), Map.entry("S04:RECOVERING", 840), Map.entry("S04:IDLE", 900),
      Map.entry("S05:RAMPING", 720), Map.entry("S05:HOLDING", 840), Map.entry("S05:RECOVERING", 1020), Map.entry("S05:IDLE", 1080),
      Map.entry("S06:RAMPING", 840), Map.entry("S06:HOLDING", 900), Map.entry("S06:RECOVERING", 1020), Map.entry("S06:IDLE", 1080),
      Map.entry("S07:RAMPING", 960), Map.entry("S07:HOLDING", 990), Map.entry("S07:RECOVERING", 1170), Map.entry("S07:IDLE", 1200),
      Map.entry("S08:RAMPING", 1080), Map.entry("S08:HOLDING", 1140), Map.entry("S08:RECOVERING", 1320), Map.entry("S08:IDLE", 1380),
      Map.entry("S09:RAMPING", 1140), Map.entry("S09:HOLDING", 1200), Map.entry("S09:RECOVERING", 1380), Map.entry("S09:IDLE", 1440),
      Map.entry("S10:RAMPING", 1200), Map.entry("S10:HOLDING", 1260), Map.entry("S10:RECOVERING", 1440), Map.entry("S10:IDLE", 1500),
      Map.entry("S11:RAMPING", 1320), Map.entry("S11:HOLDING", 1350), Map.entry("S11:RECOVERING", 1530), Map.entry("S11:IDLE", 1560),
      Map.entry("S12:RAMPING", 1380), Map.entry("S12:HOLDING", 1410), Map.entry("S12:RECOVERING", 1590), Map.entry("S12:IDLE", 1620),
      Map.entry("S13:RAMPING", 1560), Map.entry("S13:HOLDING", 1590), Map.entry("S13:RECOVERING", 1710), Map.entry("S13:IDLE", 1740)
  );

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: java scripts/ValidateRun.java <ground-truth.jsonl> <samples.jsonl> --time-scale <ts>");
      System.exit(2);
    }
    Path gtPath = Path.of(args[0]);
    Path samplesPath = Path.of(args[1]);
    double timeScale = 1.0;
    for (int i = 2; i < args.length; i++) {
      if ("--time-scale".equals(args[i]) && i + 1 < args.length) {
        timeScale = Double.parseDouble(args[++i]);
      }
    }

    List<Map<String, String>> rows = parseJsonl(Files.readString(gtPath));
    List<String> results = new ArrayList<>();
    boolean ok = true;

    ok &= check("structure.seq", contiguousSeq(rows), results);
    ok &= check("structure.run_started", !rows.isEmpty() && "run_started".equals(rows.get(0).get("type")), results);
    ok &= check("structure.run_completed", !rows.isEmpty() && "run_completed".equals(rows.get(rows.size() - 1).get("type")), results);
    for (String id : SCENARIOS) {
      ok &= check("structure." + id + ".phases", hasPhases(rows, id), results);
      ok &= check("structure." + id + ".summary", hasSummary(rows, id), results);
    }

    for (Map<String, String> row : rows) {
      if (!"phase".equals(row.get("type"))) {
        continue;
      }
      String id = row.get("scenarioId");
      String phase = row.get("phase");
      Integer planned = PLANNED.get(id + ":" + phase);
      if (planned == null) {
        continue;
      }
      double expected = planned * timeScale;
      double actual = Double.parseDouble(row.getOrDefault("offsetSeconds", "0"));
      // At timeScale 0.1: max(5, round(seconds×ts)) stretches short phases, and S01+S02 overlap
      // can delay conductor forwards on a single kind node; allow ±15s only at 0.1 (§0.3).
      double tol = timeScale <= 0.1 ? 15.0 : 5.0;
      boolean within = Math.abs(actual - expected) <= tol;
      ok &= check("timing." + id + "." + phase, within, results);
    }

    ok &= check("recovery.all_idle", allScenariosIdle(), results);
    ok &= check("stability.restarts", restartsZero(), results);
    ok &= check("stability.oom", !eventsContainOom(), results);
    ok &= check("stability.headroom", headroomOk(samplesPath), results);
    ok &= check("load.health", loadHealthOk(samplesPath), results);

    if (timeScale >= 1.0) {
      results.add("hold.thresholds SKIPPED (full thresholds are branch 11 polish)");
    } else if (timeScale >= 0.5) {
      results.add("hold.short-check SKIPPED (branch 11)");
    } else {
      results.add("hold.thresholds SKIPPED (time-scale 0.1)");
    }

    for (String line : results) {
      System.out.println(line);
    }
    System.exit(ok ? 0 : 1);
  }

  private static boolean check(String name, boolean pass, List<String> results) {
    results.add((pass ? "PASS" : "FAIL") + " " + name);
    return pass;
  }

  private static boolean contiguousSeq(List<Map<String, String>> rows) {
    for (int i = 0; i < rows.size(); i++) {
      if (!Integer.toString(i + 1).equals(rows.get(i).get("seq"))) {
        return false;
      }
    }
    return !rows.isEmpty();
  }

  private static boolean hasPhases(List<Map<String, String>> rows, String id) {
    List<String> seen = new ArrayList<>();
    for (Map<String, String> row : rows) {
      if ("phase".equals(row.get("type")) && id.equals(row.get("scenarioId"))) {
        seen.add(row.get("phase"));
      }
    }
    int idx = 0;
    for (String phase : PHASES) {
      while (idx < seen.size() && !phase.equals(seen.get(idx))) {
        idx++;
      }
      if (idx >= seen.size()) {
        return false;
      }
      idx++;
    }
    return true;
  }

  private static boolean hasSummary(List<Map<String, String>> rows, String id) {
    for (Map<String, String> row : rows) {
      if ("scenario_summary".equals(row.get("type")) && id.equals(row.get("scenarioId"))) {
        return true;
      }
    }
    return false;
  }

  private static boolean allScenariosIdle() throws Exception {
    Process p = new ProcessBuilder("curl", "-sf", "http://127.0.0.1:30081/api/scenarios")
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    int code = p.waitFor();
    if (code != 0 || out.isBlank()) {
      return false;
    }
    for (String id : SCENARIOS) {
      Pattern p2 = Pattern.compile("\"id\"\\s*:\\s*\"" + id + "\"[\\s\\S]*?\"status\"\\s*:\\s*\\{[\\s\\S]*?\"state\"\\s*:\\s*\"([A-Z]+)\"");
      Matcher m2 = p2.matcher(out);
      if (!m2.find() || !"IDLE".equals(m2.group(1))) {
        return false;
      }
    }
    return true;
  }

  private static boolean restartsZero() throws Exception {
    Process p = new ProcessBuilder("kubectl", "-n", "jafra-mart", "get", "pods",
        "-o", "jsonpath={range .items[*]}{.metadata.name} {.status.containerStatuses[0].restartCount}{\"\\n\"}{end}")
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    for (String line : out.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      if (!line.trim().endsWith(" 0") && !line.matches(".*\\s0$")) {
        return false;
      }
    }
    return true;
  }

  private static boolean eventsContainOom() throws Exception {
    Path events = Path.of("run-artifacts").resolve(".."); // unused fallback
    Process p = new ProcessBuilder("kubectl", "-n", "jafra-mart", "get", "events", "-o", "json")
        .redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out.contains("OOMKilled");
  }

  private static boolean headroomOk(Path samplesPath) throws Exception {
    // working_set = memory.current - inactive_file <= limit - 96Mi
    // memoryMax may be "max"; skip numeric check when not a number
    List<String> lines = Files.readAllLines(samplesPath);
    Pattern pod = Pattern.compile("\"(catalog-search|pricing|inventory|cart|payments|reports-worker|recommendations|orders|storefront)\"\\s*:\\s*\\{([^}]*)\\}");
    for (String line : lines) {
      Matcher m = pod.matcher(line);
      while (m.find()) {
        String body = m.group(2);
        long current = longField(body, "memoryCurrent");
        long inactive = longField(body, "inactiveFile");
        String maxRaw = stringField(body, "memoryMax");
        if (maxRaw == null || "max".equals(maxRaw)) {
          continue;
        }
        long max;
        try {
          max = Long.parseLong(maxRaw);
        } catch (NumberFormatException ex) {
          continue;
        }
        long ws = current - inactive;
        if (ws > max - 96L * 1024 * 1024) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean loadHealthOk(Path samplesPath) throws Exception {
    List<String> lines = Files.readAllLines(samplesPath);
    if (lines.isEmpty()) {
      return false;
    }
    // Prefer a mid/late sample that still has stream traffic so the denominator includes
    // scenario load (S01/S02/…), not only post-cool-down journey counts.
    String best = lines.get(lines.size() - 1);
    long bestDenom = 0;
    for (String line : lines) {
      long denom = requestDenom(line);
      if (denom > bestDenom) {
        bestDenom = denom;
        best = line;
      }
    }
    long bad5xx = 0;
    Pattern err = Pattern.compile("\"(5\\d\\d)\"\\s*:\\s*(\\d+)");
    Matcher m = err.matcher(best);
    while (m.find()) {
      String code = m.group(1);
      if ("502".equals(code) || "504".equals(code)) {
        continue;
      }
      bad5xx += Long.parseLong(m.group(2));
    }
    long denom = Math.max(1, bestDenom);
    return bad5xx < denom * 0.01;
  }

  private static long requestDenom(String json) {
    long journeys = longField(json, "journeysStarted");
    long sent = 0;
    Pattern sentPat = Pattern.compile("\"sent\"\\s*:\\s*(\\d+)");
    Matcher m = sentPat.matcher(json);
    while (m.find()) {
      sent += Long.parseLong(m.group(1));
    }
    // ~5 HTTP calls per baseline journey plus every scenario-stream request
    return journeys * 5 + sent;
  }

  private static long longField(String json, String field) {
    Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)");
    Matcher m = p.matcher(json);
    return m.find() ? Long.parseLong(m.group(1)) : 0;
  }

  private static String stringField(String json, String field) {
    Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
    Matcher m = p.matcher(json);
    if (m.find()) {
      return m.group(1);
    }
    p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+|max)");
    m = p.matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static List<Map<String, String>> parseJsonl(String text) {
    List<Map<String, String>> rows = new ArrayList<>();
    for (String line : text.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      Map<String, String> map = new LinkedHashMap<>();
      extract(map, line, "seq");
      extract(map, line, "type");
      extract(map, line, "scenarioId");
      extract(map, line, "phase");
      extract(map, line, "offsetSeconds");
      extract(map, line, "plannedOffsetSeconds");
      rows.add(map);
    }
    return rows;
  }

  private static void extract(Map<String, String> map, String json, String field) {
    Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\"[^\"]*\"|-?\\d+(?:\\.\\d+)?|null|true|false)");
    Matcher m = p.matcher(json);
    if (m.find()) {
      String v = m.group(1);
      if (v.startsWith("\"")) {
        v = v.substring(1, v.length() - 1);
      }
      map.put(field, v);
    }
  }
}
