package com.jaframart.loadgen;

import com.jaframart.scenario.Seeds;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Main {
  public static void main(String[] args) throws Exception {
    String storefrontUrl = env("STOREFRONT_URL", "http://storefront:8080");
    Map<String, String> serviceUrls = parseServiceUrls(env("SERVICE_URLS",
        "catalog-search=http://catalog-search:8080,pricing=http://pricing:8080,inventory=http://inventory:8080,"
            + "cart=http://cart:8080,payments=http://payments:8080,orders=http://orders:8080,"
            + "recommendations=http://recommendations:8080,reports-worker=http://reports-worker:8080"));
    Path groundTruthDir = Path.of(env("GROUND_TRUTH_DIR", "/data/ground-truth"));
    long seed = Seeds.globalSeed();
    double baseJps = Double.parseDouble(env("LOADGEN_BASE_JPS", "12"));
    boolean autostart = Boolean.parseBoolean(env("LOADGEN_AUTOSTART_BASELINE", "true"));

    HttpClients httpClients = new HttpClients();
    GroundTruthWriter groundTruth = new GroundTruthWriter(groundTruthDir);
    LoadEngine load = new LoadEngine(storefrontUrl, serviceUrls, httpClients, seed);
    load.setBaseJps(baseJps);
    ScenarioCatalog catalog = new ScenarioCatalog();
    ScriptDef script = ScriptDef.loadDefault();
    Conductor conductor = new Conductor(catalog, script, load, groundTruth, serviceUrls, httpClients, seed);
    ApiServer api = new ApiServer(conductor, load, groundTruth);
    api.start(8080);

    System.out.println("loadgen listening on :8080, waiting for storefront readiness at " + storefrontUrl);
    load.waitForStorefront();
    System.out.println("storefront ready; logging in 200 customers + admin");
    load.loginAll();
    System.out.println("customersLoggedIn=" + load.customersLoggedIn());
    if (autostart) {
      load.startBaseline();
      System.out.println("baseline load started at " + baseJps + " jps");
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      api.stop();
      httpClients.close();
    }));

    Thread.currentThread().join();
  }

  private static String env(String key, String defaultValue) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static Map<String, String> parseServiceUrls(String raw) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String part : raw.split(",")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      map.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
    }
    return map;
  }
}
