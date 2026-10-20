package com.jaframart.orders.store;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Test-only store; production uses {@link JdbcOrderStore}. */
public final class InMemoryOrderStore implements OrderStore {
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
      .withZone(ZoneOffset.UTC);

  private final AtomicLong seq = new AtomicLong();
  private final AtomicLong ids = new AtomicLong();
  private final CopyOnWriteArrayList<OrderRecord> orders = new CopyOnWriteArrayList<>();

  @Override
  public String allocateOrderNumber() {
    long n = seq.incrementAndGet();
    return "JM-" + DAY.format(Instant.now()) + "-" + String.format("%06d", n);
  }

  @Override
  public OrderRecord insert(OrderRecord order) {
    long id = ids.incrementAndGet();
    OrderRecord saved = new OrderRecord(
        id,
        order.orderNumber(),
        order.customerId(),
        order.customerName(),
        order.status(),
        order.subtotalCents(),
        order.discountCents(),
        order.totalCents(),
        order.currency(),
        order.couponCode(),
        order.paymentId(),
        order.createdAt(),
        List.copyOf(order.items()));
    orders.add(saved);
    return saved;
  }

  @Override
  public Optional<OrderRecord> findByNumber(String orderNumber) {
    return orders.stream().filter(o -> o.orderNumber().equals(orderNumber)).findFirst();
  }

  @Override
  public List<OrderRecord> listByCustomer(String customerId, int limit) {
    return orders.stream()
        .filter(o -> o.customerId().equals(customerId))
        .sorted(Comparator.comparing(OrderRecord::createdAt).reversed().thenComparing(OrderRecord::id).reversed())
        .limit(limit)
        .toList();
  }

  @Override
  public List<OrderRecord> recent(int limit) {
    return orders.stream()
        .sorted(Comparator.comparing(OrderRecord::createdAt).reversed().thenComparing(OrderRecord::id).reversed())
        .limit(limit)
        .toList();
  }

  @Override
  public Map<String, Object> summary() {
    List<OrderRecord> placed = orders.stream().filter(o -> "PLACED".equals(o.status())).toList();
    Instant end = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    Instant start = end.minus(29, ChronoUnit.MINUTES);
    Instant fiveMinAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
    Map<String, long[]> byMinute = new LinkedHashMap<>();
    for (int i = 0; i < 30; i++) {
      byMinute.put(MINUTE.format(start.plus(i, ChronoUnit.MINUTES)), new long[]{0L, 0L});
    }
    for (OrderRecord o : placed) {
      Instant m = o.createdAt().truncatedTo(ChronoUnit.MINUTES);
      long[] slot = byMinute.get(MINUTE.format(m));
      if (slot != null) {
        slot[0]++;
        slot[1] += o.totalCents();
      }
    }
    List<Map<String, Object>> ordersPerMinute = new ArrayList<>();
    for (Map.Entry<String, long[]> e : byMinute.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("minute", e.getKey());
      row.put("orders", e.getValue()[0]);
      row.put("revenueCents", e.getValue()[1]);
      ordersPerMinute.add(row);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ordersTotal", (long) placed.size());
    body.put("revenueCents", placed.stream().mapToLong(OrderRecord::totalCents).sum());
    body.put("revenueLast5mCents",
        placed.stream().filter(o -> !o.createdAt().isBefore(fiveMinAgo)).mapToLong(OrderRecord::totalCents).sum());
    body.put("ordersPerMinute", ordersPerMinute);
    body.put("activeCustomers", placed.stream().map(OrderRecord::customerId).collect(Collectors.toSet()).size());
    return body;
  }

  @Override
  public List<Map<String, Object>> topCustomers(int limit) {
    Map<String, List<OrderRecord>> byCustomer = orders.stream()
        .filter(o -> "PLACED".equals(o.status()))
        .collect(Collectors.groupingBy(OrderRecord::customerId));
    return byCustomer.entrySet().stream()
        .map(e -> {
          List<OrderRecord> list = e.getValue();
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("customerId", e.getKey());
          row.put("customerName", list.getFirst().customerName());
          row.put("orders", (long) list.size());
          row.put("totalCents", list.stream().mapToLong(OrderRecord::totalCents).sum());
          return row;
        })
        .sorted(Comparator
            .comparingLong((Map<String, Object> m) -> (Long) m.get("totalCents")).reversed()
            .thenComparing(m -> (String) m.get("customerId")))
        .limit(limit)
        .toList();
  }
}
