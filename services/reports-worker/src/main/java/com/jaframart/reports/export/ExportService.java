package com.jaframart.reports.export;

import com.jaframart.reports.invoice.InvoiceWriter;
import com.jaframart.scenario.Seeds;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

@Component
public class ExportService {
  private final InvoiceWriter invoices;
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicLong exportsCompleted = new AtomicLong();
  private final Semaphore exportSlots = new Semaphore(2, true);
  private final AtomicInteger configuredPermits = new AtomicInteger(2);
  private volatile double naiveIntensity;

  public ExportService(InvoiceWriter invoices) {
    this.invoices = invoices;
  }

  public void setNaiveIntensity(double intensity) {
    this.naiveIntensity = Math.max(0, intensity);
  }

  public synchronized void applyGuard(boolean growthFrozen) {
    int desired = growthFrozen ? 1 : 2;
    int current = configuredPermits.get();
    if (desired == current) {
      return;
    }
    if (desired < current) {
      exportSlots.tryAcquire();
      configuredPermits.set(desired);
    } else {
      exportSlots.release();
      configuredPermits.set(desired);
    }
  }

  public Map<String, Object> exportOrders(int rows) throws InterruptedException, IOException {
    if (rows < 1_000 || rows > 50_000) {
      throw new IllegalArgumentException("rows must be 1000..50000");
    }
    boolean acquired = exportSlots.tryAcquire(30, TimeUnit.SECONDS);
    if (!acquired) {
      throw new ExportBusyException("export slots busy");
    }
    long start = System.nanoTime();
    try {
      Result result;
      if (naiveIntensity > 0) {
        result = exportNaive(rows);
      } else {
        result = exportBaseline(rows);
      }
      exportsCompleted.incrementAndGet();
      long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      return Map.of(
          "rows", result.rows(),
          "bytes", result.bytes(),
          "sha256", result.sha256(),
          "millis", millis);
    } finally {
      exportSlots.release();
    }
  }

  public long exportsCompleted() {
    return exportsCompleted.get();
  }

  public long youngGcCount() {
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if ("PS Scavenge".equals(bean.getName())) {
        return bean.getCollectionCount();
      }
    }
    return 0L;
  }

  public void resetMetrics() {
    exportsCompleted.set(0);
  }

  Result exportBaseline(int rows) throws IOException {
    return exportBaseline(rows, Seeds.globalSeed());
  }

  Result exportBaseline(int rows, long seed) throws IOException {
    MessageDigest digest = sha256();
    long bytes;
    try (DigestOutputStream dos = new DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest);
         Writer writer = new OutputStreamWriter(dos, StandardCharsets.UTF_8)) {
      bytes = streamRows(writer, rows, seed);
      writer.flush();
    }
    return new Result(rows, bytes, HexFormat.of().formatHex(digest.digest()));
  }

  /** Naive multi-copy export used by S06 (name kept for allocation samples). */
  Result exportNaive(int rows) throws IOException {
    List<Map<String, Object>> list = buildLeanRows(rows, Seeds.globalSeed());
    String json = mapper.writeValueAsString(list);
    list.clear();
    byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
    json = null;
    ByteArrayOutputStream gzipped = new ByteArrayOutputStream(Math.max(1024, utf8.length / 3));
    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
      gzip.write(utf8);
    }
    utf8 = null;
    byte[] payload = gzipped.toByteArray();
    MessageDigest digest = sha256();
    digest.update(payload);
    return new Result(rows, payload.length, HexFormat.of().formatHex(digest.digest()));
  }

  private long streamRows(Writer writer, int rows, long seed) throws IOException {
    List<Map<String, Object>> projection = invoices.projectionSnapshot(rows);
    SplittableRandom random = new SplittableRandom(seed);
    long bytes = 0;
    for (int i = 0; i < rows; i++) {
      Map<String, Object> row;
      if (i < projection.size()) {
        row = projection.get(i);
      } else {
        row = syntheticRow(i, random);
      }
      String line = row.get("orderNumber") + "," + row.get("customerId") + "," + row.get("totalCents") + "\n";
      writer.write(line);
      bytes += StandardCharsets.UTF_8.encode(line).remaining();
    }
    return bytes;
  }

  /**
   * Lean boxed rows for S06: enough allocation for young-GC churn (~60–80 MiB across
   * the four copies) without OOMing two concurrent exports in a 256 MiB heap.
   */
  private List<Map<String, Object>> buildLeanRows(int rows, long seed) {
    List<Map<String, Object>> projection = invoices.projectionSnapshot(rows);
    SplittableRandom random = new SplittableRandom(seed);
    List<Map<String, Object>> list = new ArrayList<>(rows);
    for (int i = 0; i < rows; i++) {
      Map<String, Object> row = new LinkedHashMap<>(8);
      if (i < projection.size()) {
        Map<String, Object> src = projection.get(i);
        row.put("orderNumber", src.get("orderNumber"));
        row.put("customerId", src.get("customerId"));
        row.put("totalCents", src.get("totalCents"));
      } else {
        row.put("orderNumber", "JM-SYN-" + i);
        row.put("customerId", "c" + Math.floorMod(random.nextInt(), 200));
        row.put("totalCents", Integer.valueOf(1000 + random.nextInt(50_000)));
      }
      row.put("i", Integer.valueOf(i));
      row.put("a", Integer.valueOf(random.nextInt()));
      row.put("b", Integer.valueOf(random.nextInt()));
      row.put("c", Long.valueOf(random.nextLong()));
      list.add(row);
    }
    return list;
  }

  private static Map<String, Object> syntheticRow(int index, SplittableRandom random) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("orderNumber", "JM-SYN-" + index);
    row.put("customerId", "customer" + String.format("%03d", Math.floorMod(random.nextInt(), 200) + 1));
    row.put("totalCents", 1000L + random.nextInt(50_000));
    return row;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  record Result(int rows, long bytes, String sha256) {}

  public static final class ExportBusyException extends RuntimeException {
    public ExportBusyException(String message) {
      super(message);
    }
  }
}
