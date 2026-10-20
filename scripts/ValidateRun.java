import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates an e2e ground-truth file and samples.jsonl (§20.2–§20.3).
 * timeScale 1.0 uses the full-run hold and recovery table.
 * timeScale 0.5 uses the §19.3 short-check numbers.
 * timeScale 0.1 checks structure, timing, recovery to IDLE, stability, and load health only.
 */
public class ValidateRun {
  private static final String[] SCENARIOS = {
      "S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "S11", "S12", "S13"
  };
  private static final String[] PHASES = {"RAMPING", "HOLDING", "RECOVERING", "IDLE"};
  private static final String[] JVM = {
      "catalog-search", "pricing", "inventory", "cart", "payments",
      "reports-worker", "recommendations", "orders", "storefront"
  };
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
  private static final double MIB = 1024d * 1024d;
  private static final long HEADROOM_BYTES = 96L * 1024L * 1024L;

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

    List<Map<String, Object>> rows = new ArrayList<>();
    for (String line : Files.readString(gtPath).split("\n")) {
      if (!line.isBlank()) {
        rows.add(asMap(Json.parse(line)));
      }
    }
    List<Map<String, Object>> samples = new ArrayList<>();
    if (Files.exists(samplesPath)) {
      for (String line : Files.readString(samplesPath).split("\n")) {
        if (!line.isBlank()) {
          samples.add(asMap(Json.parse(line)));
        }
      }
    }
    samples.sort(Comparator.comparingDouble(ValidateRun::elapsed));

    List<String> results = new ArrayList<>();
    boolean ok = true;
    boolean full = timeScale >= 0.999;
    boolean shortCheck = timeScale >= 0.5 && !full;
    results.add("INFO timeScale=" + timeScale + " groundTruth=" + rows.size() + " samples=" + samples.size());

    ok &= check("structure.seq", contiguousSeq(rows), results);
    ok &= check("structure.run_started", !rows.isEmpty() && "run_started".equals(str(rows.get(0).get("type"))), results);
    ok &= check("structure.run_completed", !rows.isEmpty() && "run_completed".equals(str(rows.get(rows.size() - 1).get("type"))), results);
    for (String id : SCENARIOS) {
      ok &= check("structure." + id + ".phases", hasPhases(rows, id), results);
      ok &= check("structure." + id + ".summary", hasSummary(rows, id), results);
    }

    double tol = timeScale <= 0.1 ? 15.0 : 5.0;
    for (Map<String, Object> row : rows) {
      if (!"phase".equals(str(row.get("type")))) {
        continue;
      }
      String id = str(row.get("scenarioId"));
      String phase = str(row.get("phase"));
      Double planned = number(row.get("plannedOffsetSeconds"));
      if (planned == null || planned == 0d) {
        Integer fallback = PLANNED.get(id + ":" + phase);
        planned = fallback == null ? null : fallback.doubleValue();
      }
      if (planned == null) {
        continue;
      }
      double expected = planned * timeScale;
      double actual = number(row.get("offsetSeconds")) == null ? 0 : number(row.get("offsetSeconds"));
      boolean within = Math.abs(actual - expected) <= tol;
      ok &= check("timing." + id + "." + phase + " actual=" + round1(actual) + " expected=" + round1(expected), within, results);
    }

    ok &= check("recovery.all_idle", allScenariosIdle(samples), results);
    ok &= check("stability.restarts", restartsZero(samplesPath), results);
    ok &= check("stability.oom", !eventsContainOom(samplesPath), results);
    ok &= headroom(samples, results);
    peaks(samples, results);
    ok &= check("load.health", loadHealthOk(samples), results);
    ok &= check("load.readiness", readinessOk(samples), results);

    if (full || shortCheck) {
      ok &= baseline(samples, timeScale, results);
      for (String id : SCENARIOS) {
        ok &= hold(id, samples, rows, timeScale, full, results);
        ok &= recovery(id, samples, rows, timeScale, full, results);
      }
    } else {
      results.add("INFO hold.thresholds skipped (time-scale 0.1)");
      results.add("INFO recovery.metrics skipped (time-scale 0.1)");
    }

    for (String line : results) {
      System.out.println(line);
    }
    System.exit(ok ? 0 : 1);
  }

  private static boolean hold(String id, List<Map<String, Object>> samples, List<Map<String, Object>> rows,
                              double timeScale, boolean full, List<String> results) {
    Double holdStart = phaseOffset(rows, id, "HOLDING");
    Double recoverStart = phaseOffset(rows, id, "RECOVERING");
    if (holdStart == null || recoverStart == null) {
      return check("hold." + id + " missing phase window", false, results);
    }
    Map<String, Object> first = firstIn(samples, holdStart, recoverStart);
    Map<String, Object> last = lastIn(samples, holdStart, recoverStart);
    Map<String, Object> atRecover = nearest(samples, recoverStart, 20);
    if (first == null || last == null || atRecover == null) {
      return check("hold." + id + " no samples in hold window", false, results);
    }
    return switch (id) {
      case "S01" -> holdS01(first, last, full, results);
      case "S02" -> {
        Map<String, Object> mid = nearest(samples, (holdStart + recoverStart) / 2.0, 20);
        yield check("hold.S02 hotSkuLockWaitMsP95=" + fmt(metric(mid, "S02", "hotSkuLockWaitMsP95")),
            mid != null && metric(mid, "S02", "hotSkuLockWaitMsP95") >= (full ? 50 : 20), results);
      }
      case "S03" -> holdS03(samples, recoverStart, first, last, full, results);
      case "S04" -> holdS04(atRecover, full, results);
      case "S05" -> {
        double bytes = metric(atRecover, "S05", "cacheBytes");
        double need = (full ? 200 : 50) * MIB;
        yield check("hold.S05 cacheBytesMiB=" + round1(bytes / MIB), bytes >= need, results);
      }
      case "S06" -> holdS06(samples, timeScale, first, last, full, results);
      case "S07" -> holdS07(first, last, full, results);
      case "S08" -> {
        double live = metric(atRecover, "S08", "hiddenClassesLive");
        yield check("hold.S08 hiddenClassesLive=" + round1(live), live >= (full ? 4500 : 1500), results);
      }
      case "S09" -> holdS09(samples, rows, first, last, atRecover, full, results);
      case "S10" -> holdS10(samples, timeScale, atRecover, full, results);
      case "S11" -> {
        double delta = metric(last, "S11", "fsyncs") - metric(first, "S11", "fsyncs");
        yield check("hold.S11 fsyncsDelta=" + round1(delta), delta >= (full ? 20_000 : 3_000), results);
      }
      case "S12" -> holdS12(samples, timeScale, first, last, atRecover, full, results);
      case "S13" -> {
        double end = metric(last, "S13", "heartbeatStallsOver100ms");
        double delta = end - metric(first, "S13", "heartbeatStallsOver100ms");
        yield full
            ? check("hold.S13 stallsDelta=" + round1(delta), delta >= 20, results)
            : check("hold.S13 stalls=" + round1(end), end >= 3, results);
      }
      default -> true;
    };
  }

  private static boolean holdS01(Map<String, Object> first, Map<String, Object> last, boolean full, List<String> results) {
    double nr = cg(last, "catalog-search", "nrThrottled") - cg(first, "catalog-search", "nrThrottled");
    double usec = cg(last, "catalog-search", "throttledUsec") - cg(first, "catalog-search", "throttledUsec");
    if (full) {
      return check("hold.S01 nrThrottledDelta=" + round1(nr) + " throttledUsecDelta=" + round1(usec),
          nr >= 600 && usec >= 60_000_000, results);
    }
    return check("hold.S01 nrThrottledDelta=" + round1(nr), nr >= 30, results);
  }

  private static boolean holdS03(List<Map<String, Object>> samples, double recoverStart,
                                 Map<String, Object> first, Map<String, Object> last, boolean full, List<String> results) {
    if (full) {
      Map<String, Object> at = nearest(samples, recoverStart, 20);
      double p50 = lat(at, "p50Ms");
      return check("hold.S03 checkoutP50Ms=" + round1(p50), p50 >= 1000, results);
    }
    double max = 0;
    for (Map<String, Object> sample : List.of(first, last)) {
      max = Math.max(max, metric(sample, "S03", "baseLatencyMs"));
    }
    return check("hold.S03 baseLatencyMs=" + round1(max), max >= 800, results);
  }

  private static boolean holdS04(Map<String, Object> atRecover, boolean full, List<String> results) {
    double threads = metric(atRecover, "S04", "threadCount");
    double pools = metric(atRecover, "S04", "leakedPools");
    if (full) {
      return check("hold.S04 threadCount=" + round1(threads), threads >= 300, results);
    }
    return check("hold.S04 leakedPools=" + round1(pools) + " threadCount=" + round1(threads),
        pools >= 4 && threads >= 90, results);
  }

  private static boolean holdS06(List<Map<String, Object>> samples, double timeScale,
                                 Map<String, Object> first, Map<String, Object> last, boolean full, List<String> results) {
    double exports = metric(last, "S06", "exportsCompleted") - metric(first, "S06", "exportsCompleted");
    double young = metric(last, "S06", "youngGcCount") - metric(first, "S06", "youngGcCount");
    if (!full) {
      return check("hold.S06 exportsDelta=" + round1(exports) + " youngGcDelta=" + round1(young),
          exports >= 20 && young >= 20, results);
    }
    double dt = Math.max(0.001, elapsed(last) - elapsed(first));
    double holdRate = young / dt;
    Map<String, Object> b0 = firstIn(samples, 60 * timeScale, 210 * timeScale);
    Map<String, Object> b1 = lastIn(samples, 60 * timeScale, 210 * timeScale);
    double baseRate = 0;
    if (b0 != null && b1 != null && elapsed(b1) > elapsed(b0)) {
      baseRate = (metric(b1, "S06", "youngGcCount") - metric(b0, "S06", "youngGcCount")) / (elapsed(b1) - elapsed(b0));
    }
    return check("hold.S06 exportsDelta=" + round1(exports) + " youngRate=" + round1(holdRate) + " baselineRate=" + round1(baseRate),
        exports >= 150 && holdRate >= 5 * baseRate, results);
  }

  private static boolean holdS07(Map<String, Object> first, Map<String, Object> last, boolean full, List<String> results) {
    double delta = metric(last, "S07", "exceptionsThrown") - metric(first, "S07", "exceptionsThrown");
    double dt = Math.max(0.001, elapsed(last) - elapsed(first));
    if (full) {
      return check("hold.S07 exceptionsPerSec=" + round1(delta / dt), delta / dt >= 1000, results);
    }
    return check("hold.S07 exceptionsDelta=" + round1(delta), delta >= 20_000, results);
  }

  private static boolean holdS09(List<Map<String, Object>> samples, List<Map<String, Object>> rows,
                                 Map<String, Object> first, Map<String, Object> last, Map<String, Object> atRecover,
                                 boolean full, List<String> results) {
    double pipes = metric(last, "S09", "pipesCreated") - metric(first, "S09", "pipesCreated");
    double code = metric(atRecover, "S09", "codeCacheUsedPct");
    if (full) {
      return check("hold.S09 codeCacheUsedPct=" + round1(code) + " pipesDelta=" + round1(pipes),
          code >= 75 && pipes >= 2000, results);
    }
    Double ramp = phaseOffset(rows, "S09", "RAMPING");
    Map<String, Object> before = ramp == null ? null : nearest(samples, Math.max(0, ramp - 1), 30);
    double beforeCode = before == null ? 0 : metric(before, "S09", "codeCacheUsedPct");
    double endPipes = metric(atRecover, "S09", "pipesCreated");
    return check("hold.S09 pipesCreated=" + round1(endPipes) + " codeCacheUsedPct=" + round1(code) + " before=" + round1(beforeCode),
        endPipes >= 300 && code >= beforeCode + 15, results);
  }

  private static boolean holdS10(List<Map<String, Object>> samples, double timeScale, Map<String, Object> atRecover,
                                 boolean full, List<String> results) {
    double off = metric(atRecover, "S10", "offHeapBytes");
    if (!full) {
      return check("hold.S10 offHeapMiB=" + round1(off / MIB), off >= 60 * MIB, results);
    }
    Map<String, Object> base = lastIn(samples, 60 * timeScale, 210 * timeScale);
    double baseMem = base == null ? 0 : cg(base, "catalog-search", "memoryCurrent");
    double nowMem = cg(atRecover, "catalog-search", "memoryCurrent");
    return check("hold.S10 offHeapMiB=" + round1(off / MIB) + " memoryGrowthMiB=" + round1((nowMem - baseMem) / MIB),
        off >= 90 * MIB && nowMem >= baseMem + 150 * MIB, results);
  }

  private static boolean holdS12(List<Map<String, Object>> samples, double timeScale,
                                 Map<String, Object> first, Map<String, Object> last, Map<String, Object> atRecover,
                                 boolean full, List<String> results) {
    if (!full) {
      double pinned = metric(atRecover, "S12", "pinnedScoringCalls");
      double p95 = metric(atRecover, "S12", "authorizeMsP95");
      return check("hold.S12 pinned=" + round1(pinned) + " authorizeMsP95=" + round1(p95),
          pinned >= 50 && p95 >= 200, results);
    }
    double pinned = metric(last, "S12", "pinnedScoringCalls") - metric(first, "S12", "pinnedScoringCalls");
    double checkout = lat(atRecover, "p95Ms");
    double base = medianLatency(samples, 60 * timeScale, 210 * timeScale, "p95Ms");
    return check("hold.S12 pinnedDelta=" + round1(pinned) + " checkoutP95=" + round1(checkout) + " baselineP95=" + round1(base),
        pinned >= 300 && checkout >= 2 * base, results);
  }

  private static boolean recovery(String id, List<Map<String, Object>> samples, List<Map<String, Object>> rows,
                                  double timeScale, boolean full, List<String> results) {
    Double idleAt = phaseOffset(rows, id, "IDLE");
    if (idleAt == null) {
      return check("recovery." + id + " missing IDLE", false, results);
    }
    double target = idleAt + 60 * timeScale;
    Map<String, Object> at = nearest(samples, target, 25);
    if (at == null) {
      return check("recovery." + id + " no sample at IDLE+60s", false, results);
    }
    Map<String, Object> holdEnd = lastIn(samples, phaseOffset(rows, id, "HOLDING"), phaseOffset(rows, id, "RECOVERING"));
    return switch (id) {
      case "S01" -> check("recovery.S01 fuzzyExpansions=" + fmt(metric(at, "S01", "fuzzyExpansions")),
          Math.round(metric(at, "S01", "fuzzyExpansions")) == 1, results);
      case "S02" -> check("recovery.S02 hotSkuLockWaitMsP95=" + fmt(metric(at, "S02", "hotSkuLockWaitMsP95")),
          metric(at, "S02", "hotSkuLockWaitMsP95") <= 5, results);
      case "S03" -> check("recovery.S03 baseLatencyMs=" + fmt(metric(at, "S03", "baseLatencyMs")),
          metric(at, "S03", "baseLatencyMs") <= 100, results);
      case "S04" -> full
          ? check("recovery.S04 threadCount=" + fmt(metric(at, "S04", "threadCount")),
              metric(at, "S04", "threadCount") <= 130, results)
          : check("recovery.S04 leakedPools=" + fmt(metric(at, "S04", "leakedPools")),
              Math.round(metric(at, "S04", "leakedPools")) == 0, results);
      case "S05" -> {
        double bytes = metric(at, "S05", "cacheBytes");
        double entries = metric(at, "S05", "cacheEntries");
        yield check("recovery.S05 cacheMiB=" + round1(bytes / MIB) + " entries=" + round1(entries),
            bytes <= 70 * MIB && entries <= 1000, results);
      }
      case "S06" -> {
        Map<String, Object> idleSample = firstAtOrAfter(samples, idleAt);
        double dt = idleSample == null ? 0 : elapsed(at) - elapsed(idleSample);
        double rate = dt <= 0 ? 99 : (metric(at, "S06", "youngGcCount") - metric(idleSample, "S06", "youngGcCount")) / dt;
        yield check("recovery.S06 youngGcPerSec=" + round1(rate), rate < 1, results);
      }
      case "S07" -> {
        Map<String, Object> idleSample = firstAtOrAfter(samples, idleAt);
        double dt = idleSample == null ? 0 : elapsed(at) - elapsed(idleSample);
        double rate = dt <= 0 ? 999 : (metric(at, "S07", "exceptionsThrown") - metric(idleSample, "S07", "exceptionsThrown")) / dt;
        yield check("recovery.S07 exceptionsPerSec=" + round1(rate), rate < 100, results);
      }
      case "S08" -> {
        double live = metric(at, "S08", "hiddenClassesLive");
        double now = metric(at, "S08", "metaspaceUsedMiB");
        double holdMeta = holdEnd == null ? now : metric(holdEnd, "S08", "metaspaceUsedMiB");
        double drop = full ? 10 : 5;
        yield check("recovery.S08 hidden=" + round1(live) + " metaspaceMiB=" + round1(now) + " holdMiB=" + round1(holdMeta),
            Math.round(live) == 0 && now <= holdMeta - drop, results);
      }
      case "S09" -> recoveryS09(samples, at, full, results);
      case "S10" -> {
        double off = metric(at, "S10", "offHeapBytes");
        double disk = metric(at, "S10", "diskBytes");
        yield check("recovery.S10 offHeapMiB=" + round1(off / MIB) + " diskBytes=" + round1(disk),
            off <= 20 * MIB && Math.round(disk) == 0, results);
      }
      case "S11" -> check("recovery.S11 queueDepth=" + fmt(metric(at, "S11", "queueDepth")),
          Math.round(metric(at, "S11", "queueDepth")) == 0, results);
      case "S12" -> check("recovery.S12 authorizeMsP95=" + fmt(metric(at, "S12", "authorizeMsP95")),
          metric(at, "S12", "authorizeMsP95") <= 120, results);
      case "S13" -> {
        Map<String, Object> idleSample = firstAtOrAfter(samples, idleAt);
        Map<String, Object> later = nearest(samples, idleAt + 10, 15);
        double a = idleSample == null ? -1 : metric(idleSample, "S13", "heartbeatStallsOver100ms");
        double b = later == null ? -1 : metric(later, "S13", "heartbeatStallsOver100ms");
        yield check("recovery.S13 stallsAtIdle=" + round1(a) + " stallsPlus10s=" + round1(b),
            idleSample != null && later != null && b <= a, results);
      }
      default -> true;
    };
  }

  private static boolean recoveryS09(List<Map<String, Object>> samples, Map<String, Object> at, boolean full, List<String> results) {
    double live = metric(at, "S09", "livePipes");
    if (!full) {
      return check("recovery.S09 livePipes=" + round1(live), Math.round(live) == 0, results);
    }
    double code = metric(at, "S09", "codeCacheUsedPct");
    double start = samples.isEmpty() ? 0 : metric(samples.get(0), "S09", "codeCacheUsedPct");
    return check("recovery.S09 livePipes=" + round1(live) + " codeCacheUsedPct=" + round1(code) + " runStart=" + round1(start),
        Math.round(live) == 0 && code <= start + 10, results);
  }

  private static boolean baseline(List<Map<String, Object>> samples, double timeScale, List<String> results) {
    double start = 60 * timeScale;
    double end = 210 * timeScale;
    Map<String, Object> first = firstIn(samples, start, end);
    Map<String, Object> last = lastIn(samples, start, end);
    if (first == null || last == null) {
      return check("baseline.window missing samples", false, results);
    }
    double nr = cg(last, "catalog-search", "nrThrottled") - cg(first, "catalog-search", "nrThrottled");
    boolean nrOk = check("baseline.nr_throttled delta=" + round1(nr), nr <= 100, results);
    boolean p50Ok = true;
    int seen = 0;
    double worst = 0;
    for (Map<String, Object> sample : samples) {
      double e = elapsed(sample);
      if (e < start || e > end) {
        continue;
      }
      if (lat(sample, "count") <= 0) {
        continue;
      }
      seen++;
      worst = Math.max(worst, lat(sample, "p50Ms"));
    }
    p50Ok = check("baseline.checkout_p50Ms worst=" + round1(worst) + " samples=" + seen, seen > 0 && worst <= 400, results);
    return nrOk && p50Ok;
  }

  private static boolean headroom(List<Map<String, Object>> samples, List<String> results) {
    boolean ok = true;
    for (String pod : JVM) {
      boolean seen = false;
      double worstWs = 0;
      double worstLimit = 0;
      boolean violated = false;
      for (Map<String, Object> sample : samples) {
        Double max = cgOrNull(sample, pod, "memoryMax");
        Double current = cgOrNull(sample, pod, "memoryCurrent");
        if (max == null || current == null || max <= 0) {
          continue;
        }
        seen = true;
        double inactive = cg(sample, pod, "inactiveFile");
        double ws = current - inactive;
        if (ws > worstWs) {
          worstWs = ws;
          worstLimit = max;
        }
        if (ws > max - HEADROOM_BYTES) {
          violated = true;
        }
      }
      if (!seen) {
        ok &= check("stability.headroom." + pod + " no cgroup samples", false, results);
      } else if (violated) {
        ok &= check("stability.headroom." + pod + " workingSetMiB=" + round1(worstWs / MIB)
            + " limitMiB=" + round1(worstLimit / MIB) + " ceilingMiB=" + round1((worstLimit - HEADROOM_BYTES) / MIB), false, results);
      } else {
        ok &= check("stability.headroom." + pod + " workingSetMiB=" + round1(worstWs / MIB)
            + " limitMiB=" + round1(worstLimit / MIB), true, results);
      }
    }
    return ok;
  }

  private static void peaks(List<Map<String, Object>> samples, List<String> results) {
    for (String pod : JVM) {
      double worstWs = -1;
      double limit = 0;
      for (Map<String, Object> sample : samples) {
        Double max = cgOrNull(sample, pod, "memoryMax");
        Double current = cgOrNull(sample, pod, "memoryCurrent");
        if (max == null || current == null || max <= 0) {
          continue;
        }
        double ws = current - cg(sample, pod, "inactiveFile");
        if (ws > worstWs) {
          worstWs = ws;
          limit = max;
        }
      }
      if (worstWs >= 0) {
        results.add("INFO peak." + pod + " workingSetMiB=" + round1(worstWs / MIB) + " limitMiB=" + round1(limit / MIB));
      }
    }
  }

  private static boolean loadHealthOk(List<Map<String, Object>> samples) {
    if (samples.isEmpty()) {
      return false;
    }
    Map<String, Object> best = samples.get(samples.size() - 1);
    long bad = 0;
    Object load = best.get("load");
    if (load instanceof Map<?, ?> loadMap) {
      Object errors = loadMap.get("errorsByStatus");
      if (errors instanceof Map<?, ?> errMap) {
        for (Map.Entry<?, ?> entry : errMap.entrySet()) {
          String code = String.valueOf(entry.getKey());
          if (code.startsWith("5") && !"502".equals(code) && !"504".equals(code) && entry.getValue() instanceof Number n) {
            bad += n.longValue();
          }
        }
      }
    }
    long denom = Math.max(1, requestDenom(best));
    return bad < denom * 0.01;
  }

  private static long requestDenom(Map<String, Object> sample) {
    long journeys = 0;
    long sent = 0;
    Object load = sample.get("load");
    if (!(load instanceof Map<?, ?> loadMap)) {
      return 0;
    }
    if (loadMap.get("journeysStarted") instanceof Number n) {
      journeys = n.longValue();
    }
    Object streams = loadMap.get("streams");
    if (streams instanceof Map<?, ?> streamMap) {
      for (Object stream : streamMap.values()) {
        if (stream instanceof Map<?, ?> one && one.get("sent") instanceof Number n) {
          sent += n.longValue();
        }
      }
    }
    return journeys * 5 + sent;
  }

  private static boolean readinessOk(List<Map<String, Object>> samples) {
    int streak = 0;
    boolean saw = false;
    for (Map<String, Object> sample : samples) {
      Object ready = sample.get("storefrontReady");
      if (!(ready instanceof Boolean)) {
        continue;
      }
      saw = true;
      if (Boolean.TRUE.equals(ready)) {
        streak = 0;
      } else {
        streak++;
        if (streak >= 3) {
          return false;
        }
      }
    }
    return saw;
  }

  private static boolean allScenariosIdle(List<Map<String, Object>> samples) throws Exception {
    Boolean live = liveIdle();
    if (live != null) {
      return live;
    }
    if (samples.isEmpty()) {
      return false;
    }
    Map<String, Object> last = samples.get(samples.size() - 1);
    for (String id : SCENARIOS) {
      String state = scenarioState(last, id);
      if (!"IDLE".equals(state)) {
        return false;
      }
    }
    return true;
  }

  private static Boolean liveIdle() {
    try {
      Process p = new ProcessBuilder("curl", "-sf", "--max-time", "5", "http://127.0.0.1:30081/api/scenarios")
          .redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      if (p.waitFor() != 0 || out.isBlank()) {
        return null;
      }
      Object parsed = Json.parse(out);
      if (!(parsed instanceof List<?> list)) {
        return null;
      }
      for (String id : SCENARIOS) {
        boolean found = false;
        for (Object row : list) {
          if (!(row instanceof Map<?, ?> map) || !id.equals(String.valueOf(map.get("id")))) {
            continue;
          }
          found = true;
          Object status = map.get("status");
          String state = status instanceof Map<?, ?> st ? String.valueOf(st.get("state")) : "";
          if (!"IDLE".equals(state)) {
            return false;
          }
        }
        if (!found) {
          return false;
        }
      }
      return true;
    } catch (Exception ex) {
      return null;
    }
  }

  private static boolean restartsZero(Path samplesPath) throws Exception {
    String live = command(List.of("kubectl", "-n", "jafra-mart", "get", "pods",
        "-o", "jsonpath={range .items[*]}{.metadata.name} {.status.containerStatuses[0].restartCount}{\"\\n\"}{end}"));
    if (live != null) {
      for (String line : live.split("\n")) {
        if (line.isBlank()) {
          continue;
        }
        if (!line.matches(".*\\s0$")) {
          return false;
        }
      }
      return true;
    }
    Path pods = samplesPath.getParent().resolve("pods.txt");
    if (!Files.exists(pods)) {
      return false;
    }
    for (String line : Files.readAllLines(pods)) {
      if (line.isBlank() || line.startsWith("NAME")) {
        continue;
      }
      String[] parts = line.trim().split("\\s+");
      if (parts.length >= 4 && !parts[3].equals("0") && !parts[3].startsWith("0")) {
        return false;
      }
    }
    return true;
  }

  private static boolean eventsContainOom(Path samplesPath) throws Exception {
    String live = command(List.of("kubectl", "-n", "jafra-mart", "get", "events", "-o", "json"));
    if (live != null && live.contains("OOMKilled")) {
      return true;
    }
    Path events = samplesPath.getParent().resolve("events.txt");
    if (Files.exists(events) && Files.readString(events).contains("OOMKilled")) {
      return true;
    }
    return live != null && live.contains("OOMKilled");
  }

  private static String command(List<String> cmd) {
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      if (p.waitFor() != 0) {
        return null;
      }
      return out;
    } catch (Exception ex) {
      return null;
    }
  }

  private static boolean contiguousSeq(List<Map<String, Object>> rows) {
    for (int i = 0; i < rows.size(); i++) {
      Double seq = number(rows.get(i).get("seq"));
      if (seq == null || seq.longValue() != i + 1L) {
        return false;
      }
    }
    return !rows.isEmpty();
  }

  private static boolean hasPhases(List<Map<String, Object>> rows, String id) {
    List<String> seen = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      if ("phase".equals(str(row.get("type"))) && id.equals(str(row.get("scenarioId")))) {
        seen.add(str(row.get("phase")));
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

  private static boolean hasSummary(List<Map<String, Object>> rows, String id) {
    for (Map<String, Object> row : rows) {
      if ("scenario_summary".equals(str(row.get("type"))) && id.equals(str(row.get("scenarioId")))) {
        return true;
      }
    }
    return false;
  }

  private static Double phaseOffset(List<Map<String, Object>> rows, String id, String phase) {
    if (rows == null) {
      return null;
    }
    for (Map<String, Object> row : rows) {
      if ("phase".equals(str(row.get("type"))) && id.equals(str(row.get("scenarioId"))) && phase.equals(str(row.get("phase")))) {
        return number(row.get("offsetSeconds"));
      }
    }
    return null;
  }

  private static Map<String, Object> firstIn(List<Map<String, Object>> samples, Double start, Double end) {
    if (start == null || end == null) {
      return null;
    }
    for (Map<String, Object> sample : samples) {
      double e = elapsed(sample);
      if (e >= start && e <= end) {
        return sample;
      }
    }
    return null;
  }

  private static Map<String, Object> lastIn(List<Map<String, Object>> samples, Double start, Double end) {
    if (start == null || end == null) {
      return null;
    }
    Map<String, Object> found = null;
    for (Map<String, Object> sample : samples) {
      double e = elapsed(sample);
      if (e >= start && e <= end) {
        found = sample;
      }
    }
    return found;
  }

  private static Map<String, Object> firstAtOrAfter(List<Map<String, Object>> samples, double t) {
    for (Map<String, Object> sample : samples) {
      if (elapsed(sample) >= t) {
        return sample;
      }
    }
    return null;
  }

  private static Map<String, Object> nearest(List<Map<String, Object>> samples, double target, double maxSkew) {
    Map<String, Object> best = null;
    double bestD = Double.MAX_VALUE;
    for (Map<String, Object> sample : samples) {
      double d = Math.abs(elapsed(sample) - target);
      if (d < bestD) {
        bestD = d;
        best = sample;
      }
    }
    return bestD <= maxSkew ? best : null;
  }

  private static double medianLatency(List<Map<String, Object>> samples, double start, double end, String field) {
    List<Double> values = new ArrayList<>();
    for (Map<String, Object> sample : samples) {
      double e = elapsed(sample);
      if (e < start || e > end || lat(sample, "count") <= 0) {
        continue;
      }
      values.add(lat(sample, field));
    }
    if (values.isEmpty()) {
      return 0;
    }
    values.sort(Double::compareTo);
    return values.get(values.size() / 2);
  }

  @SuppressWarnings("unchecked")
  private static double metric(Map<String, Object> sample, String id, String name) {
    if (sample == null) {
      return 0;
    }
    Object scenarios = sample.get("scenarios");
    if (!(scenarios instanceof List<?> list)) {
      return 0;
    }
    for (Object row : list) {
      if (!(row instanceof Map<?, ?> map) || !id.equals(String.valueOf(map.get("id")))) {
        continue;
      }
      Object status = map.get("status");
      if (!(status instanceof Map<?, ?> st)) {
        return 0;
      }
      Object metrics = st.get("metrics");
      if (!(metrics instanceof Map<?, ?> mm)) {
        return 0;
      }
      Double n = number(mm.get(name));
      return n == null ? 0 : n;
    }
    return 0;
  }

  private static String scenarioState(Map<String, Object> sample, String id) {
    Object scenarios = sample.get("scenarios");
    if (!(scenarios instanceof List<?> list)) {
      return "";
    }
    for (Object row : list) {
      if (!(row instanceof Map<?, ?> map) || !id.equals(String.valueOf(map.get("id")))) {
        continue;
      }
      Object status = map.get("status");
      if (status instanceof Map<?, ?> st && st.get("state") != null) {
        return String.valueOf(st.get("state"));
      }
    }
    return "";
  }

  private static double cg(Map<String, Object> sample, String pod, String field) {
    Double n = cgOrNull(sample, pod, field);
    return n == null ? 0 : n;
  }

  @SuppressWarnings("unchecked")
  private static Double cgOrNull(Map<String, Object> sample, String pod, String field) {
    if (sample == null) {
      return null;
    }
    Object cgroups = sample.get("cgroups");
    if (!(cgroups instanceof Map<?, ?> map)) {
      return null;
    }
    Object podBody = map.get(pod);
    if (!(podBody instanceof Map<?, ?> body)) {
      return null;
    }
    return number(body.get(field));
  }

  private static double lat(Map<String, Object> sample, String field) {
    if (sample == null) {
      return 0;
    }
    Object latency = sample.get("latency");
    if (!(latency instanceof Map<?, ?> map)) {
      return 0;
    }
    Double n = number(map.get(field));
    return n == null ? 0 : n;
  }

  private static double elapsed(Map<String, Object> sample) {
    Double n = number(sample.get("elapsedSeconds"));
    return n == null ? 0 : n;
  }

  private static Double number(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof String s) {
      try {
        return Double.parseDouble(s.trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private static boolean check(String name, boolean pass, List<String> results) {
    results.add((pass ? "PASS" : "FAIL") + " " + name);
    return pass;
  }

  private static String fmt(double v) {
    return round1(v);
  }

  private static String round1(double v) {
    return String.format(java.util.Locale.ROOT, "%.1f", v);
  }

  /** Minimal JSON parser for objects, arrays, strings, numbers, booleans, and null. */
  static final class Json {
    private final String s;
    private int i;

    private Json(String s) {
      this.s = s;
    }

    static Object parse(String text) {
      Json json = new Json(text);
      Object value = json.value();
      return value;
    }

    private Object value() {
      skip();
      if (i >= s.length()) {
        throw new IllegalArgumentException("truncated json");
      }
      char c = s.charAt(i);
      if (c == '{') {
        return object();
      }
      if (c == '[') {
        return array();
      }
      if (c == '"') {
        return string();
      }
      if (c == 't' || c == 'f') {
        return bool();
      }
      if (c == 'n') {
        i += 4;
        return null;
      }
      return number();
    }

    private Map<String, Object> object() {
      Map<String, Object> map = new LinkedHashMap<>();
      i++;
      skip();
      if (peek('}')) {
        i++;
        return map;
      }
      while (i < s.length()) {
        skip();
        String key = string();
        skip();
        if (i < s.length() && s.charAt(i) == ':') {
          i++;
        }
        map.put(key, value());
        skip();
        if (peek('}')) {
          i++;
          break;
        }
        if (peek(',')) {
          i++;
        }
      }
      return map;
    }

    private List<Object> array() {
      List<Object> list = new ArrayList<>();
      i++;
      skip();
      if (peek(']')) {
        i++;
        return list;
      }
      while (i < s.length()) {
        list.add(value());
        skip();
        if (peek(']')) {
          i++;
          break;
        }
        if (peek(',')) {
          i++;
        }
      }
      return list;
    }

    private String string() {
      if (!peek('"')) {
        throw new IllegalArgumentException("expected string at " + i);
      }
      i++;
      StringBuilder sb = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') {
          break;
        }
        if (c == '\\' && i < s.length()) {
          char e = s.charAt(i++);
          sb.append(switch (e) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '"' -> '"';
            case '\\' -> '\\';
            default -> e;
          });
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    private Object bool() {
      if (s.startsWith("true", i)) {
        i += 4;
        return Boolean.TRUE;
      }
      i += 5;
      return Boolean.FALSE;
    }

    private Double number() {
      int start = i;
      if (peek('-')) {
        i++;
      }
      while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
        i++;
      }
      if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        i++;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
          i++;
        }
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
          i++;
        }
      }
      return Double.parseDouble(s.substring(start, i));
    }

    private void skip() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    private boolean peek(char c) {
      return i < s.length() && s.charAt(i) == c;
    }
  }
}
