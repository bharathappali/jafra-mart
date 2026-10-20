package com.jaframart.inventory.stock;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Product;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class StockLedger {
  private static final String HOT_SKU = CatalogData.FLASH_SKU;
  private static final int DEFAULT_STOCK = 500;
  private static final int HOT_STOCK = 1_000_000;

  private final ConcurrentHashMap<String, SkuStock> stocks = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();
  private final AuditTrail auditTrail;
  private final AtomicInteger reservationSeq = new AtomicInteger();
  private final AtomicLong hotSkuCalls = new AtomicLong();
  private final List<Long> hotWaitSamples = new ArrayList<>();
  private volatile boolean scenarioActive;

  public StockLedger(AuditTrail auditTrail) {
    this.auditTrail = auditTrail;
  }

  @PostConstruct
  void seed() {
    for (Product product : CatalogData.products()) {
      int qty = HOT_SKU.equals(product.sku()) ? HOT_STOCK : DEFAULT_STOCK;
      stocks.put(product.sku(), new SkuStock(product.sku(), qty));
    }
  }

  public void setScenarioIntensity(double ci) {
    this.scenarioActive = ci > 0;
    auditTrail.setParkNanos(ci > 0 ? Math.round(3_000_000L * ci) : 0);
  }

  public void fillHotWaitlist(long seed, double ci) {
    SkuStock stock = stocks.get(HOT_SKU);
    if (stock == null) {
      return;
    }
    int size = (int) Math.round(20_000 * ci);
    size = Math.min(20_000, Math.max(0, size));
    List<SkuStock.WaitlistEntry> entries = new ArrayList<>(size);
    java.util.SplittableRandom random = new java.util.SplittableRandom(seed);
    for (int i = 0; i < size; i++) {
      entries.add(new SkuStock.WaitlistEntry(random.nextLong()));
    }
    synchronized (stock) {
      stock.setWaitlist(entries);
    }
  }

  public void clearHotWaitlist() {
    SkuStock stock = stocks.get(HOT_SKU);
    if (stock == null) {
      return;
    }
    synchronized (stock) {
      stock.clearWaitlist();
    }
    setScenarioIntensity(0);
  }

  public Map<String, Object> availability(String sku) {
    SkuStock stock = stocks.get(sku);
    if (stock == null) {
      return null;
    }
    long waitStart = System.nanoTime();
    synchronized (stock) {
      onHotEnter(stock, waitStart);
      return Map.of(
          "sku", sku,
          "available", stock.available(),
          "reserved", stock.reserved(),
          "waitlist", stock.waitlistSize());
    }
  }

  public Reservation reserve(String orderNumber, List<Line> lines) {
    List<HeldLine> prepared = new ArrayList<>();
    for (Line line : lines) {
      SkuStock stock = stocks.get(line.sku());
      if (stock == null) {
        throw new IllegalArgumentException("unknown sku " + line.sku());
      }
      prepared.add(new HeldLine(stock, line.quantity()));
    }
    List<HeldLine> held = new ArrayList<>();
    for (HeldLine line : prepared) {
      long waitStart = System.nanoTime();
      synchronized (line.stock()) {
        onHotEnter(line.stock(), waitStart);
        if (!line.stock().tryReserve(line.quantity())) {
          for (HeldLine done : held) {
            synchronized (done.stock()) {
              done.stock().release(done.quantity());
            }
          }
          return null;
        }
        held.add(line);
      }
    }
    String id = "R-" + String.format("%06d", reservationSeq.incrementAndGet());
    Reservation reservation = new Reservation(id, orderNumber, held, Instant.now().plusSeconds(300), "HELD");
    reservations.put(id, reservation);
    return reservation;
  }

  public Reservation commit(String id) {
    Reservation reservation = reservations.get(id);
    if (reservation == null || !"HELD".equals(reservation.status())) {
      return null;
    }
    for (HeldLine line : reservation.lines()) {
      long waitStart = System.nanoTime();
      synchronized (line.stock()) {
        onHotEnter(line.stock(), waitStart);
        line.stock().commit(line.quantity());
      }
    }
    reservation.status = "COMMITTED";
    return reservation;
  }

  public Reservation release(String id) {
    Reservation reservation = reservations.get(id);
    if (reservation == null || !"HELD".equals(reservation.status())) {
      return null;
    }
    for (HeldLine line : reservation.lines()) {
      long waitStart = System.nanoTime();
      synchronized (line.stock()) {
        onHotEnter(line.stock(), waitStart);
        line.stock().release(line.quantity());
      }
    }
    reservation.status = "RELEASED";
    return reservation;
  }

  @Scheduled(fixedRate = 1000)
  void sweep() {
    Instant now = Instant.now();
    for (Reservation reservation : List.copyOf(reservations.values())) {
      if ("HELD".equals(reservation.status()) && reservation.expiresAt().isBefore(now)) {
        release(reservation.id());
      }
    }
  }

  public long hotSkuCalls() {
    return hotSkuCalls.get();
  }

  public double hotSkuLockWaitMsP95() {
    synchronized (hotWaitSamples) {
      if (hotWaitSamples.isEmpty()) {
        return 0;
      }
      List<Long> copy = new ArrayList<>(hotWaitSamples);
      copy.sort(Long::compareTo);
      int idx = Math.min(copy.size() - 1, (int) Math.ceil(copy.size() * 0.95) - 1);
      return copy.get(Math.max(0, idx)) / 1_000_000.0;
    }
  }

  public void resetMetrics() {
    synchronized (hotWaitSamples) {
      hotWaitSamples.clear();
    }
    hotSkuCalls.set(0);
  }

  private void onHotEnter(SkuStock stock, long waitStart) {
    if (!(HOT_SKU.equals(stock.sku()) && scenarioActive)) {
      return;
    }
    long entered = System.nanoTime();
    hotSkuCalls.incrementAndGet();
    synchronized (hotWaitSamples) {
      hotWaitSamples.add(entered - waitStart);
      while (hotWaitSamples.size() > 10_000) {
        hotWaitSamples.remove(0);
      }
    }
    stock.recomputeWaitlistPositions();
    auditTrail.record(stock.sku(), "hot");
  }

  public record Line(String sku, int quantity) {}

  public static final class HeldLine {
    private final SkuStock stock;
    private final int quantity;

    HeldLine(SkuStock stock, int quantity) {
      this.stock = stock;
      this.quantity = quantity;
    }

    SkuStock stock() { return stock; }
    int quantity() { return quantity; }
  }

  public static final class Reservation {
    private final String id;
    private final String orderNumber;
    private final List<HeldLine> lines;
    private final Instant expiresAt;
    private String status;

    Reservation(String id, String orderNumber, List<HeldLine> lines, Instant expiresAt, String status) {
      this.id = id;
      this.orderNumber = orderNumber;
      this.lines = List.copyOf(lines);
      this.expiresAt = expiresAt;
      this.status = status;
    }

    public String id() { return id; }
    public String orderNumber() { return orderNumber; }
    public List<HeldLine> lines() { return lines; }
    public Instant expiresAt() { return expiresAt; }
    public String status() { return status; }
  }
}
