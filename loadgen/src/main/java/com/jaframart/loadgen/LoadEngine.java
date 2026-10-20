package com.jaframart.loadgen;

import com.jaframart.catalog.model.WordLists;
import com.jaframart.scenario.Seeds;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class LoadEngine {
  private static final char[] CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
  private static final String[] S01_QUERIES = {
      "frostd glass lmp", "frosted glas lamp", "forsted glass lamp", "frosted glss lanp", "frostedglass lamp"
  };

  private final String storefrontUrl;
  private final Map<String, String> serviceUrls;
  private final HttpClients httpClients;
  private final long seed;
  private final List<HttpClients.Session> customers = new ArrayList<>();
  private HttpClients.Session admin;
  private final HttpClients.Session direct;

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "load-scheduler");
    t.setDaemon(true);
    return t;
  });
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  private final AtomicBoolean baselineOn = new AtomicBoolean(false);
  private volatile double baseJps = 12.0;
  private double accumulator;
  private java.util.SplittableRandom loadRng;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final LongAdder dropped = new LongAdder();
  private final LongAdder journeysStarted = new LongAdder();
  private final ConcurrentHashMap<String, LongAdder> errorsByStatus = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, LatencyWindow> latencies = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ScenarioStream> streams = new ConcurrentHashMap<>();
  private final List<ScheduledFuture<?>> baselineTasks = new ArrayList<>();

  LoadEngine(String storefrontUrl, Map<String, String> serviceUrls, HttpClients httpClients, long seed) {
    this.storefrontUrl = storefrontUrl.replaceAll("/$", "");
    this.serviceUrls = Map.copyOf(serviceUrls);
    this.httpClients = httpClients;
    this.seed = seed;
    this.direct = httpClients.session();
    this.loadRng = new java.util.SplittableRandom(seed);
  }

  void waitForStorefront() throws InterruptedException {
    HttpClients.Session probe = httpClients.session();
    while (true) {
      try {
        HttpResponse<byte[]> res = probe.send(HttpClients.request(
            URI.create(storefrontUrl + "/actuator/health/readiness")).GET().build());
        if (res.statusCode() == 200) {
          return;
        }
      } catch (Exception ignored) {
        // keep polling
      }
      Thread.sleep(2000);
    }
  }

  void loginAll() throws Exception {
    customers.clear();
    for (int i = 1; i <= 200; i++) {
      String id = String.format("customer%03d", i);
      HttpClients.Session session = httpClients.session();
      login(session, id, "customer");
      customers.add(session);
    }
    admin = httpClients.session();
    login(admin, "admin", "admin");
  }

  int customersLoggedIn() {
    return customers.size();
  }

  boolean baselineRunning() {
    return baselineOn.get();
  }

  void startBaseline() {
    if (!baselineOn.compareAndSet(false, true)) {
      return;
    }
    accumulator = 0;
    loadRng = new java.util.SplittableRandom(seed);
    baselineTasks.add(scheduler.scheduleAtFixedRate(this::baselineTick, 0, 100, TimeUnit.MILLISECONDS));
    baselineTasks.add(scheduler.scheduleAtFixedRate(() -> fireAdmin("/api/admin/summary"), 10, 10, TimeUnit.SECONDS));
    baselineTasks.add(scheduler.scheduleAtFixedRate(
        () -> fireAdmin("/api/admin/orders/recent?limit=50"), 30, 30, TimeUnit.SECONDS));
    baselineTasks.add(scheduler.scheduleAtFixedRate(
        () -> fireAdmin("/api/admin/reports/export?rows=5000"), 60, 60, TimeUnit.SECONDS));
  }

  synchronized void setBaseJps(double jps) {
    if (jps < 0 || jps > 40) {
      throw new IllegalArgumentException("baseJps must be 0-40");
    }
    this.baseJps = jps;
  }

  double baseJps() {
    return baseJps;
  }

  void startScenarioStream(String id, java.util.function.DoubleSupplier intensity) {
    streams.compute(id, (key, existing) -> {
      if (existing != null) {
        existing.stop();
      }
      ScenarioStream stream = new ScenarioStream(id, intensity);
      stream.start();
      return stream;
    });
  }

  void stopScenarioStream(String id) {
    ScenarioStream stream = streams.remove(id);
    if (stream != null) {
      stream.stop();
    }
  }

  void stopAllScenarioStreams() {
    for (String id : List.copyOf(streams.keySet())) {
      stopScenarioStream(id);
    }
  }

  Map<String, Object> loadSnapshot() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("baseJps", baseJps);
    body.put("inFlight", inFlight.get());
    body.put("dropped", dropped.sum());
    body.put("journeysStarted", journeysStarted.sum());
    Map<String, Long> errors = new LinkedHashMap<>();
    errorsByStatus.forEach((k, v) -> errors.put(k, v.sum()));
    body.put("errorsByStatus", errors);
    Map<String, Object> streamSnap = new LinkedHashMap<>();
    streams.forEach((id, stream) -> streamSnap.put(id, stream.snapshot()));
    body.put("streams", streamSnap);
    return body;
  }

  Map<String, Object> latency(String step, int windowSeconds) {
    LatencyWindow window = latencies.computeIfAbsent(step, ignored -> new LatencyWindow());
    return window.snapshot(step, windowSeconds);
  }

  void putFlash(boolean active) {
    String catalog = serviceUrls.get("catalog-search");
    if (catalog == null) {
      return;
    }
    workers.execute(() -> {
      try {
        String body = "{\"active\":" + active + "}";
        HttpRequest req = HttpClients.request(URI.create(catalog + "/api/flash"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
        direct.send(req);
      } catch (Exception ignored) {
        // best effort
      }
    });
  }

  void seedCartsForS07() {
    for (HttpClients.Session session : customers) {
      workers.execute(() -> {
        try {
          post(session, "/api/cart/items", "{\"sku\":\"SKU-00001\",\"quantity\":1}");
        } catch (Exception ignored) {
          // ignore
        }
      });
    }
  }

  private void baselineTick() {
    if (!baselineOn.get()) {
      return;
    }
    accumulator += baseJps * 0.1;
    while (accumulator >= 1.0) {
      accumulator -= 1.0;
      if (inFlight.get() >= 256) {
        dropped.increment();
        continue;
      }
      int roll = loadRng.nextInt(100);
      HttpClients.Session customer = customers.get(loadRng.nextInt(200));
      Runnable journey;
      if (roll <= 59) {
        journey = browsePlan(customer);
      } else if (roll <= 84) {
        journey = checkoutPlan(customer);
      } else if (roll <= 94) {
        journey = couponPlan(customer);
      } else {
        journey = () -> myOrders(customer);
      }
      inFlight.incrementAndGet();
      journeysStarted.increment();
      workers.execute(() -> {
        try {
          journey.run();
        } finally {
          inFlight.decrementAndGet();
        }
      });
    }
  }

  private Runnable browsePlan(HttpClients.Session session) {
    String category = WordLists.CATEGORIES.get(loadRng.nextInt(WordLists.CATEGORIES.size()));
    int page = loadRng.nextInt(5);
    String noun = WordLists.NOUN.get(loadRng.nextInt(WordLists.NOUN.size())).toLowerCase();
    String detailSku = pickSku(loadRng);
    return () -> {
      try {
        long t0 = System.nanoTime();
        HttpResponse<byte[]> products = get(session,
            "/api/catalog/products?category=" + enc(category) + "&page=" + page + "&size=20");
        recordLatency("browse", t0);
        think();
        t0 = System.nanoTime();
        get(session, "/api/catalog/search?q=" + enc(noun) + "&limit=20");
        recordLatency("search", t0);
        think();
        get(session, "/api/catalog/products/" + detailSku);
        think();
        t0 = System.nanoTime();
        get(session, "/api/recommendations?productId=" + enc(detailSku));
        recordLatency("recommendations", t0);
        think();
        List<String> thumbs = firstSkus(products.body(), 4);
        if (thumbs.isEmpty()) {
          thumbs = List.of(detailSku, "SKU-00001", "SKU-00002", "SKU-00003");
        }
        for (String thumb : thumbs) {
          get(session, "/api/catalog/products/" + thumb + "/thumbnail?size=128");
        }
      } catch (Exception ignored) {
        // counted via status
      }
    };
  }

  private Runnable checkoutPlan(HttpClients.Session session) {
    String firstSku = pickSku(loadRng);
    int lines = 1 + loadRng.nextInt(3);
    List<String> skus = new ArrayList<>();
    List<Integer> qtys = new ArrayList<>();
    skus.add(firstSku);
    qtys.add(1 + loadRng.nextInt(2));
    for (int i = 1; i < lines; i++) {
      skus.add(pickSku(loadRng));
      qtys.add(1 + loadRng.nextInt(2));
    }
    int couponRoll = loadRng.nextInt(100);
    String coupon = couponRoll < 20 ? "WELCOME10" : couponRoll < 25 ? "JAFRA5" : null;
    String token = loadRng.nextInt(100) < 2 ? "tok_decline" : "tok_visa";
    return () -> {
      try {
        get(session, "/api/catalog/products/" + firstSku);
        think();
        for (int i = 0; i < skus.size(); i++) {
          post(session, "/api/cart/items",
              "{\"sku\":\"" + skus.get(i) + "\",\"quantity\":" + qtys.get(i) + "}");
          think();
        }
        if (coupon != null) {
          post(session, "/api/cart/coupon", "{\"code\":\"" + coupon + "\"}");
          think();
        }
        long t0 = System.nanoTime();
        post(session, "/api/checkout", "{\"cardToken\":\"" + token + "\"}");
        recordLatency("checkout", t0);
        think();
        get(session, "/api/orders/mine");
      } catch (Exception ignored) {
        // ignore
      }
    };
  }

  private Runnable couponPlan(HttpClients.Session session) {
    String sku = pickSku(loadRng);
    int bad = 1 + loadRng.nextInt(3);
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < bad; i++) {
      codes.add(randomCode(loadRng));
    }
    boolean welcome = loadRng.nextInt(100) < 50;
    return () -> {
      try {
        post(session, "/api/cart/items", "{\"sku\":\"" + sku + "\",\"quantity\":1}");
        think();
        for (String code : codes) {
          long t0 = System.nanoTime();
          post(session, "/api/cart/coupon", "{\"code\":\"" + code + "\"}");
          recordLatency("coupon", t0);
          think();
        }
        if (welcome) {
          post(session, "/api/cart/coupon", "{\"code\":\"WELCOME10\"}");
        }
      } catch (Exception ignored) {
        // ignore
      }
    };
  }

  private String pickSku(java.util.SplittableRandom rng) {
    int n = rng.nextInt(100) < 70 ? 1 + rng.nextInt(20_000) : 1 + rng.nextInt(200);
    return String.format("SKU-%05d", n);
  }

  private void myOrders(HttpClients.Session session) {
    try {
      get(session, "/api/orders/mine");
    } catch (Exception ignored) {
      // ignore
    }
  }

  private void fireAdmin(String path) {
    if (admin == null) {
      return;
    }
    workers.execute(() -> {
      try {
        get(admin, path);
      } catch (Exception ignored) {
        // ignore
      }
    });
  }

  private void think() throws InterruptedException {
    Thread.sleep(200);
  }

  private HttpResponse<byte[]> get(HttpClients.Session session, String path) throws Exception {
    HttpResponse<byte[]> res = session.send(HttpClients.request(URI.create(storefrontUrl + path)).GET().build());
    noteStatus(res.statusCode());
    return res;
  }

  private HttpResponse<byte[]> post(HttpClients.Session session, String path, String body) throws Exception {
    HttpRequest req = HttpClients.request(URI.create(storefrontUrl + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<byte[]> res = session.send(req);
    noteStatus(res.statusCode());
    return res;
  }

  private void noteStatus(int status) {
    if (status >= 400) {
      errorsByStatus.computeIfAbsent(Integer.toString(status), ignored -> new LongAdder()).increment();
    }
  }

  private void recordLatency(String step, long startNanos) {
    long ms = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    latencies.computeIfAbsent(step, ignored -> new LatencyWindow()).record(ms);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String randomCode(java.util.SplittableRandom rng) {
    char[] chars = new char[8];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)];
    }
    return new String(chars);
  }

  private static List<String> firstSkus(byte[] body, int n) {
    try {
      var tree = Json.tree(new String(body, StandardCharsets.UTF_8));
      var items = tree.get("items");
      if (items == null || !items.isArray()) {
        return List.of();
      }
      List<String> out = new ArrayList<>();
      for (int i = 0; i < items.size() && out.size() < n; i++) {
        var item = items.get(i);
        if (item.has("sku")) {
          out.add(item.get("sku").asText());
        }
      }
      return out;
    } catch (Exception ex) {
      return List.of();
    }
  }

  private void login(HttpClients.Session session, String user, String pass) throws Exception {
    String body = "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
    HttpRequest req = HttpClients.request(URI.create(storefrontUrl + "/api/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<byte[]> res = session.send(req);
    if (res.statusCode() != 200) {
      throw new IllegalStateException("login failed for " + user + ": " + res.statusCode());
    }
  }

  final class ScenarioStream {
    private final String id;
    private final java.util.function.DoubleSupplier intensity;
    private final java.util.SplittableRandom rng;
    private final AtomicInteger inFlightLocal = new AtomicInteger();
    private final LongAdder droppedLocal = new LongAdder();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private double acc;
    private double acc2;
    private int queryIdx;
    private int thumbSku;
    private long lastBurstMs;
    private volatile double lastRate;
    private ScheduledFuture<?> task;

    ScenarioStream(String id, java.util.function.DoubleSupplier intensity) {
      this.id = id;
      this.intensity = intensity;
      this.rng = new java.util.SplittableRandom(Seeds.derive(seed, id + "-load"));
      this.thumbSku = 1 + rng.nextInt(20_000);
      this.lastBurstMs = System.currentTimeMillis();
    }

    void start() {
      if (!running.compareAndSet(false, true)) {
        return;
      }
      if ("S01".equals(id)) {
        putFlash(true);
      }
      if ("S07".equals(id)) {
        seedCartsForS07();
      }
      task = scheduler.scheduleAtFixedRate(this::tick, 0, 100, TimeUnit.MILLISECONDS);
    }

    void stop() {
      running.set(false);
      if (task != null) {
        task.cancel(false);
      }
      if ("S01".equals(id)) {
        putFlash(false);
      }
      lastRate = 0;
    }

    Map<String, Object> snapshot() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rate", lastRate);
      m.put("dropped", droppedLocal.sum());
      m.put("inFlight", inFlightLocal.get());
      m.put("sent", sent.get());
      return m;
    }

    private void tick() {
      if (!running.get()) {
        return;
      }
      double ci = Math.max(0, Math.min(1, intensity.getAsDouble()));
      if (ci <= 0) {
        lastRate = 0;
        return;
      }
      switch (id) {
        case "S01" -> rate(60 * ci, () -> {
          String q = S01_QUERIES[queryIdx++ % S01_QUERIES.length];
          getAny(pickCustomer(), "/api/catalog/search?fuzzy=true&limit=20&q=" + enc(q));
        });
        case "S02" -> {
          lastRate = 200 * ci + 8 * ci;
          acc += 200 * ci * 0.1;
          while (acc >= 1.0) {
            acc -= 1.0;
            launch(() -> getAny(pickCustomer(), "/api/inventory/SKU-00042"));
          }
          acc2 += 8 * ci * 0.1;
          while (acc2 >= 1.0) {
            acc2 -= 1.0;
            launch(this::hotCheckout);
          }
        }
        case "S04" -> burst(ci);
        case "S05" -> rate(30 * ci, () -> {
          String sku = String.format("SKU-%05d", 1 + rng.nextInt(20_000));
          getAny(pickCustomer(), "/api/recommendations?productId=" + enc(sku));
        });
        case "S06" -> rate(2 * ci, () -> getAny(admin, "/api/admin/reports/export?rows=50000"));
        case "S07" -> rate(200 * ci, () -> {
          HttpClients.Session c = customers.get((int) (sent.get() % customers.size()));
          postAny(c, "/api/cart/coupon", "{\"code\":\"" + randomCode(rng) + "\"}");
        });
        case "S10" -> rate(20 * ci, () -> {
          String sku = String.format("SKU-%05d", thumbSku);
          thumbSku = thumbSku >= 20_000 ? 1 : thumbSku + 1;
          getAny(pickCustomer(), "/api/catalog/products/" + sku + "/thumbnail?size=256");
        });
        case "S11" -> rate(10 * ci, () -> postAny(admin, "/api/admin/reports/reprint", "{\"count\":1}"));
        default -> lastRate = 0;
      }
    }

    private void rate(double rps, Runnable action) {
      lastRate = rps;
      acc += rps * 0.1;
      while (acc >= 1.0) {
        acc -= 1.0;
        launch(action);
      }
    }

    private void burst(double ci) {
      long now = System.currentTimeMillis();
      if (now - lastBurstMs < 10_000) {
        return;
      }
      lastBurstMs = now;
      int n = (int) Math.round(25 * ci);
      lastRate = n;
      for (int i = 0; i < n; i++) {
        launch(this::burstCheckout);
      }
    }

    private void launch(Runnable action) {
      if (inFlightLocal.get() >= 64) {
        droppedLocal.increment();
        return;
      }
      inFlightLocal.incrementAndGet();
      sent.incrementAndGet();
      workers.execute(() -> {
        try {
          action.run();
        } finally {
          inFlightLocal.decrementAndGet();
        }
      });
    }

    private HttpClients.Session pickCustomer() {
      return customers.get(rng.nextInt(customers.size()));
    }

    private void hotCheckout() {
      HttpClients.Session session = pickCustomer();
      try {
        postAny(session, "/api/cart/items", "{\"sku\":\"SKU-00042\",\"quantity\":1}");
        postAny(session, "/api/cart/coupon", "{\"code\":\"FLASH20\"}");
        postAny(session, "/api/checkout", "{\"cardToken\":\"tok_visa\"}");
      } catch (Exception ignored) {
        // ignore
      }
    }

    private void burstCheckout() {
      HttpClients.Session session = pickCustomer();
      try {
        String sku = String.format("SKU-%05d", 1 + rng.nextInt(200));
        postAny(session, "/api/cart/items", "{\"sku\":\"" + sku + "\",\"quantity\":1}");
        postAny(session, "/api/checkout", "{\"cardToken\":\"tok_visa\"}");
      } catch (Exception ignored) {
        // ignore
      }
    }

    private void getAny(HttpClients.Session session, String path) {
      try {
        get(session, path);
      } catch (Exception ignored) {
        // ignore
      }
    }

    private void postAny(HttpClients.Session session, String path, String body) {
      try {
        post(session, path, body);
      } catch (Exception ignored) {
        // ignore
      }
    }
  }

  static final class LatencyWindow {
    private final Object lock = new Object();
    private final long[] times = new long[4096];
    private final long[] values = new long[4096];
    private int idx;

    void record(long ms) {
      synchronized (lock) {
        times[idx] = System.currentTimeMillis();
        values[idx] = ms;
        idx = (idx + 1) % times.length;
      }
    }

    Map<String, Object> snapshot(String step, int windowSeconds) {
      int window = Math.max(10, Math.min(300, windowSeconds));
      long cutoff = System.currentTimeMillis() - window * 1000L;
      List<Long> sample = new ArrayList<>();
      synchronized (lock) {
        for (int i = 0; i < times.length; i++) {
          if (times[i] >= cutoff && times[i] != 0) {
            sample.add(values[i]);
          }
        }
      }
      sample.sort(Long::compareTo);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("step", step);
      out.put("windowSeconds", window);
      out.put("count", sample.size());
      out.put("p50Ms", percentile(sample, 0.50));
      out.put("p95Ms", percentile(sample, 0.95));
      return out;
    }

    private static long percentile(List<Long> sorted, double p) {
      if (sorted.isEmpty()) {
        return 0;
      }
      int i = Math.min(sorted.size() - 1, (int) Math.round((sorted.size() - 1) * p));
      return sorted.get(i);
    }
  }
}
