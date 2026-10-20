package com.jaframart.reports.invoice;

import com.jaframart.scenario.Seeds;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.logging.Logger;

@Component
public class InvoiceWriter implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(InvoiceWriter.class.getName());
  static final int QUEUE_CAPACITY = 2_000;
  static final int MAX_FILES = 2_000;
  static final int MAX_PROJECTIONS = 5_000;
  private static final int CHUNK = 256;

  private final Path invoiceDir;
  private final BlockingQueue<WriteJob> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Thread[] workers = new Thread[4];
  private final AtomicInteger dropOldest = new AtomicInteger();
  private final AtomicLong invoicesWritten = new AtomicLong();
  private final AtomicLong fsyncs = new AtomicLong();
  private final DoubleAdder fsyncMsSum = new DoubleAdder();
  private final AtomicLong fsyncSamples = new AtomicLong();
  private final Object fsyncLock = new Object();
  private final long[] fsyncRing = new long[256];
  private int fsyncRingPos;
  private int fsyncRingSize;
  private final Deque<String> fileOrder = new ArrayDeque<>();
  private final Map<String, OrderInvoice> projections = new LinkedHashMap<>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, OrderInvoice> eldest) {
      return size() > MAX_PROJECTIONS;
    }
  };
  private final Object filesLock = new Object();
  private final Object projLock = new Object();
  private volatile double durableFraction;
  private volatile long durableSeed = Seeds.globalSeed() ^ "S11".hashCode();
  private final ThreadLocal<java.util.SplittableRandom> durableRandom =
      ThreadLocal.withInitial(() -> new java.util.SplittableRandom(durableSeed));
  private volatile boolean running = true;

  public InvoiceWriter(@Value("${INVOICE_DIR:}") String configured) throws IOException {
    String dir = configured == null || configured.isBlank()
        ? System.getProperty("java.io.tmpdir") + "/jafra-mart-invoices"
        : configured;
    this.invoiceDir = Path.of(dir);
    Files.createDirectories(this.invoiceDir);
    ThreadFactory factory = r -> {
      Thread t = new Thread(r);
      t.setDaemon(true);
      return t;
    };
    for (int i = 0; i < workers.length; i++) {
      final int n = i;
      workers[i] = factory.newThread(() -> drainLoop(n));
      workers[i].setName("invoice-writer-" + n);
      workers[i].start();
    }
  }

  public Path invoiceDir() {
    return invoiceDir;
  }

  public boolean enqueue(OrderInvoice order) {
    WriteJob job = new WriteJob(order, false);
    while (!queue.offer(job)) {
      WriteJob dropped = queue.poll();
      if (dropped != null) {
        int n = dropOldest.incrementAndGet();
        LOG.warning("invoice queue drop-oldest count=" + n + " order=" + dropped.order().orderNumber());
      }
    }
    return true;
  }

  public Map<String, Object> reprint(int count) throws IOException {
    if (count < 1 || count > 50) {
      throw new IllegalArgumentException("count must be 1..50");
    }
    List<OrderInvoice> selected = new ArrayList<>();
    synchronized (projLock) {
      List<OrderInvoice> all = new ArrayList<>(projections.values());
      for (int i = all.size() - 1; i >= 0 && selected.size() < count; i--) {
        selected.add(all.get(i));
      }
    }
    if (selected.isEmpty()) {
      throw new IllegalStateException("no invoices to reprint");
    }
    long start = System.nanoTime();
    long fsyncBefore = fsyncs.get();
    int reprinted = 0;
    for (OrderInvoice order : selected) {
      writeNow(order);
      reprinted++;
    }
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    return Map.of(
        "reprinted", reprinted,
        "fsyncs", fsyncs.get() - fsyncBefore,
        "millis", millis);
  }

  public String readText(String orderNumber) throws IOException {
    Path file = invoiceDir.resolve(orderNumber + ".txt");
    if (!Files.isRegularFile(file)) {
      return null;
    }
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  public List<Map<String, Object>> projectionSnapshot(int limit) {
    synchronized (projLock) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (OrderInvoice order : projections.values()) {
        out.add(order.toProjection());
        if (out.size() >= limit) {
          break;
        }
      }
      return out;
    }
  }

  public int projectionSize() {
    synchronized (projLock) {
      return projections.size();
    }
  }

  public long invoicesWritten() {
    return invoicesWritten.get();
  }

  public long fsyncs() {
    return fsyncs.get();
  }

  public double fsyncMsP95() {
    synchronized (fsyncLock) {
      if (fsyncRingSize == 0) {
        return 0;
      }
      long[] copy = new long[fsyncRingSize];
      for (int i = 0; i < fsyncRingSize; i++) {
        copy[i] = fsyncRing[i];
      }
      java.util.Arrays.sort(copy);
      int idx = Math.min(copy.length - 1, (int) Math.ceil(0.95 * copy.length) - 1);
      return copy[Math.max(0, idx)];
    }
  }

  public int queueDepth() {
    return queue.size();
  }

  public int fileCount() {
    synchronized (filesLock) {
      return fileOrder.size();
    }
  }

  public void setDurableFraction(double fraction, long seed) {
    this.durableFraction = Math.max(0, Math.min(1, fraction));
    this.durableSeed = seed;
    durableRandom.set(new java.util.SplittableRandom(seed));
  }

  public void resetMetrics() {
    dropOldest.set(0);
    fsyncs.set(0);
    fsyncMsSum.reset();
    fsyncSamples.set(0);
    synchronized (fsyncLock) {
      fsyncRingPos = 0;
      fsyncRingSize = 0;
    }
  }

  private void drainLoop(int workerId) {
    while (running) {
      try {
        WriteJob job = queue.poll(200, TimeUnit.MILLISECONDS);
        if (job == null) {
          continue;
        }
        writeNow(job.order());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception ex) {
        LOG.warning("invoice-writer-" + workerId + " failed: " + ex.getMessage());
      }
    }
  }

  void writeNow(OrderInvoice order) throws IOException {
    String text = InvoiceRenderer.render(order);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    Path file = invoiceDir.resolve(order.orderNumber() + ".txt");
    boolean durable = durableFraction > 0 && durableRandom.get().nextDouble() < durableFraction;
    if (durable) {
      writeDurable(file, bytes);
    } else {
      writeBaseline(file, bytes);
    }
    rememberFile(order.orderNumber());
    synchronized (projLock) {
      projections.remove(order.orderNumber());
      projections.put(order.orderNumber(), order);
    }
    invoicesWritten.incrementAndGet();
  }

  private void writeBaseline(Path file, byte[] bytes) throws IOException {
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)) {
      ch.write(ByteBuffer.wrap(bytes));
      force(ch);
    }
  }

  /** Chunked fsync path used by S11 (method name kept for AP stack matching). */
  void writeDurable(Path file, byte[] bytes) throws IOException {
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)) {
      int offset = 0;
      while (offset < bytes.length) {
        int len = Math.min(CHUNK, bytes.length - offset);
        ch.write(ByteBuffer.wrap(bytes, offset, len));
        force(ch);
        offset += len;
      }
    }
  }

  private void force(FileChannel ch) throws IOException {
    long t0 = System.nanoTime();
    ch.force(true);
    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    fsyncs.incrementAndGet();
    fsyncMsSum.add(ms);
    fsyncSamples.incrementAndGet();
    synchronized (fsyncLock) {
      fsyncRing[fsyncRingPos] = ms;
      fsyncRingPos = (fsyncRingPos + 1) % fsyncRing.length;
      fsyncRingSize = Math.min(fsyncRing.length, fsyncRingSize + 1);
    }
  }

  private void rememberFile(String orderNumber) throws IOException {
    synchronized (filesLock) {
      fileOrder.remove(orderNumber);
      fileOrder.addLast(orderNumber);
      while (fileOrder.size() > MAX_FILES) {
        String oldest = fileOrder.removeFirst();
        Files.deleteIfExists(invoiceDir.resolve(oldest + ".txt"));
      }
    }
  }

  @Override
  @PreDestroy
  public void close() {
    running = false;
    for (Thread worker : workers) {
      worker.interrupt();
    }
  }

  private record WriteJob(OrderInvoice order, boolean sync) {
    WriteJob {
      Objects.requireNonNull(order);
    }
  }
}
