import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MiniLoad {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

  public static void main(String[] args) throws Exception {
    String url = null;
    String method = "GET";
    String body = null;
    int rps = 1;
    int seconds = 1;
    int concurrency = 64;
    int burst = 0;
    int every = 10;
    List<String> headers = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--help", "-h" -> {
          System.out.println("""
              Usage: java scripts/MiniLoad.java --url <url> [--method GET|POST|PUT] [--body <json>] \\
                [--header K:V]... --rps N --seconds S [--concurrency 64] [--burst N --every S]
              Placeholders: {seq} {seq5} {rand8}
              """);
          return;
        }
        case "--url" -> url = args[++i];
        case "--method" -> method = args[++i];
        case "--body" -> body = args[++i];
        case "--header" -> headers.add(args[++i]);
        case "--rps" -> rps = Integer.parseInt(args[++i]);
        case "--seconds" -> seconds = Integer.parseInt(args[++i]);
        case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
        case "--burst" -> burst = Integer.parseInt(args[++i]);
        case "--every" -> every = Integer.parseInt(args[++i]);
        default -> throw new IllegalArgumentException(args[i]);
      }
    }
    if (url == null) {
      throw new IllegalArgumentException("--url is required");
    }
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .version(HttpClient.Version.HTTP_1_1)
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    Semaphore inflight = new Semaphore(concurrency);
    AtomicInteger sent = new AtomicInteger();
    AtomicInteger dropped = new AtomicInteger();
    Map<Integer, AtomicInteger> byStatus = new ConcurrentHashMap<>();
    List<Long> latencies = new CopyOnWriteArrayList<>();
    SplittableRandom random = new SplittableRandom(20261019L);
    AtomicInteger seq = new AtomicInteger();
    String methodFinal = method;
    String urlFinal = url;
    String bodyFinal = body;
    long end = System.nanoTime() + seconds * 1_000_000_000L;
    double acc = 0;
    long nextBurst = System.nanoTime();
    while (System.nanoTime() < end) {
      long slice = System.nanoTime();
      if (burst > 0) {
        if (System.nanoTime() >= nextBurst) {
          for (int i = 0; i < burst; i++) {
            long delayMs = (long) (i * (1000.0 / burst));
            submit(workers, inflight, sent, dropped, byStatus, latencies, client, headers, methodFinal,
                urlFinal, bodyFinal, random, seq, delayMs);
          }
          nextBurst += every * 1_000_000_000L;
        }
      } else {
        acc += rps * 0.1;
        while (acc >= 1.0 && System.nanoTime() < end) {
          acc -= 1.0;
          submit(workers, inflight, sent, dropped, byStatus, latencies, client, headers, methodFinal,
              urlFinal, bodyFinal, random, seq, 0);
        }
      }
      long remain = 100_000_000L - (System.nanoTime() - slice);
      if (remain > 0) {
        Thread.sleep(Duration.ofNanos(remain));
      }
    }
    workers.shutdown();
    workers.awaitTermination(30, TimeUnit.SECONDS);
    List<Long> sorted = new ArrayList<>(latencies);
    Collections.sort(sorted);
    long p50 = percentile(sorted, 0.50);
    long p95 = percentile(sorted, 0.95);
    StringBuilder statuses = new StringBuilder();
    byStatus.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
      if (!statuses.isEmpty()) {
        statuses.append(',');
      }
      statuses.append('"').append(e.getKey()).append("\":").append(e.getValue().get());
    });
    System.out.printf("{\"sent\":%d,\"byStatus\":{%s},\"p50Ms\":%d,\"p95Ms\":%d,\"dropped\":%d}%n",
        sent.get(), statuses, p50, p95, dropped.get());
  }

  private static void submit(ExecutorService workers, Semaphore inflight, AtomicInteger sent, AtomicInteger dropped,
                             Map<Integer, AtomicInteger> byStatus, List<Long> latencies, HttpClient client,
                             List<String> headers, String method, String url, String body, SplittableRandom random,
                             AtomicInteger seq, long delayMs) {
    if (!inflight.tryAcquire()) {
      dropped.incrementAndGet();
      return;
    }
    int n = seq.incrementAndGet();
    String rand = random8(random);
    workers.submit(() -> {
      try {
        if (delayMs > 0) {
          Thread.sleep(delayMs);
        }
        String oneUrl = fill(url, n, rand);
        String oneBody = body == null ? null : fill(body, n, rand);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(oneUrl))
            .timeout(Duration.ofSeconds(15));
        for (String header : headers) {
          int colon = header.indexOf(':');
          builder.header(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
        }
        if (oneBody != null) {
          builder.header("content-type", "application/json");
          builder.method(method, HttpRequest.BodyPublishers.ofString(oneBody));
        } else {
          builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        long t0 = System.nanoTime();
        HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        latencies.add((System.nanoTime() - t0) / 1_000_000L);
        byStatus.computeIfAbsent(response.statusCode(), k -> new AtomicInteger()).incrementAndGet();
        sent.incrementAndGet();
      } catch (Exception ex) {
        byStatus.computeIfAbsent(0, k -> new AtomicInteger()).incrementAndGet();
        sent.incrementAndGet();
      } finally {
        inflight.release();
      }
    });
  }

  private static String fill(String value, int seq, String rand) {
    String seq5 = String.format("%05d", ((seq - 1) % 20000) + 1);
    return value.replace("{seq5}", seq5).replace("{seq}", Integer.toString(seq)).replace("{rand8}", rand);
  }

  private static String random8(SplittableRandom random) {
    StringBuilder builder = new StringBuilder(8);
    for (int i = 0; i < 8; i++) {
      builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return builder.toString();
  }

  private static long percentile(List<Long> sorted, double p) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int index = (int) Math.round(p * (sorted.size() - 1));
    return sorted.get(index);
  }
}
