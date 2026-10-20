package com.jaframart.orders.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcOrderStore implements OrderStore {
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
      .withZone(ZoneOffset.UTC);

  private final JdbcTemplate jdbc;

  public JdbcOrderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String allocateOrderNumber() {
    Long seq = jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class);
    long n = seq == null ? 0L : seq;
    return "JM-" + DAY.format(Instant.now()) + "-" + String.format("%06d", n);
  }

  @Override
  @Transactional
  public OrderRecord insert(OrderRecord order) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(con -> {
      PreparedStatement ps = con.prepareStatement(
          """
          INSERT INTO orders (order_number, customer_id, customer_name, status, subtotal_cents,
            discount_cents, total_cents, currency, coupon_code, payment_id, created_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?)
          """,
          new String[]{"id"});
      ps.setString(1, order.orderNumber());
      ps.setString(2, order.customerId());
      ps.setString(3, order.customerName());
      ps.setString(4, order.status());
      ps.setLong(5, order.subtotalCents());
      ps.setLong(6, order.discountCents());
      ps.setLong(7, order.totalCents());
      ps.setString(8, order.currency() == null ? "USD" : order.currency());
      ps.setString(9, order.couponCode());
      ps.setString(10, order.paymentId());
      ps.setTimestamp(11, Timestamp.from(order.createdAt()));
      return ps;
    }, keys);
    Map<String, Object> keyMap = keys.getKeys();
    Number key = keyMap == null ? null : (Number) keyMap.get("id");
    if (key == null) {
      key = keys.getKey();
    }
    long id = key == null ? 0L : key.longValue();
    int lineNo = 1;
    for (OrderRecord.Item item : order.items()) {
      jdbc.update(
          """
          INSERT INTO order_items (order_id, line_no, sku, product_name, quantity, unit_price_cents)
          VALUES (?,?,?,?,?,?)
          """,
          id, lineNo++, item.sku(), item.productName(), item.quantity(), item.unitPriceCents());
    }
    return new OrderRecord(
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
        order.items());
  }

  @Override
  public Optional<OrderRecord> findByNumber(String orderNumber) {
    List<OrderRecord> rows = jdbc.query(
        """
        SELECT id, order_number, customer_id, customer_name, status, subtotal_cents, discount_cents,
               total_cents, currency, coupon_code, payment_id, created_at
        FROM orders WHERE order_number = ?
        """,
        (rs, i) -> mapHeader(rs.getLong("id"), rs),
        orderNumber);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    OrderRecord header = rows.getFirst();
    return Optional.of(withItems(header));
  }

  @Override
  public List<OrderRecord> listByCustomer(String customerId, int limit) {
    List<OrderRecord> headers = jdbc.query(
        """
        SELECT id, order_number, customer_id, customer_name, status, subtotal_cents, discount_cents,
               total_cents, currency, coupon_code, payment_id, created_at
        FROM orders WHERE customer_id = ?
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """,
        (rs, i) -> mapHeader(rs.getLong("id"), rs),
        customerId, limit);
    return headers.stream().map(this::withItems).toList();
  }

  @Override
  public List<OrderRecord> recent(int limit) {
    List<OrderRecord> headers = jdbc.query(
        """
        SELECT id, order_number, customer_id, customer_name, status, subtotal_cents, discount_cents,
               total_cents, currency, coupon_code, payment_id, created_at
        FROM orders
        ORDER BY created_at DESC, id DESC
        LIMIT ?
        """,
        (rs, i) -> mapHeader(rs.getLong("id"), rs),
        limit);
    return headers.stream().map(this::withItems).toList();
  }

  @Override
  public Map<String, Object> summary() {
    Long ordersTotal = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = 'PLACED'", Long.class);
    Long revenueCents = jdbc.queryForObject(
        "SELECT COALESCE(SUM(total_cents),0) FROM orders WHERE status = 'PLACED'", Long.class);
    Long revenueLast5m = jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(total_cents),0) FROM orders
        WHERE status = 'PLACED' AND created_at >= now() - interval '5 minutes'
        """,
        Long.class);
    Long activeCustomers = jdbc.queryForObject(
        "SELECT COUNT(DISTINCT customer_id) FROM orders WHERE status = 'PLACED'", Long.class);

    Instant end = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    Instant start = end.minus(29, ChronoUnit.MINUTES);
    Map<String, long[]> byMinute = new LinkedHashMap<>();
    for (int i = 0; i < 30; i++) {
      Instant m = start.plus(i, ChronoUnit.MINUTES);
      byMinute.put(MINUTE.format(m), new long[]{0L, 0L});
    }
    jdbc.query(
        """
        SELECT date_trunc('minute', created_at) AS minute,
               COUNT(*) AS orders, COALESCE(SUM(total_cents),0) AS revenue
        FROM orders
        WHERE status = 'PLACED' AND created_at >= ? AND created_at < ?
        GROUP BY 1
        """,
        (rs) -> {
          Instant minute = rs.getTimestamp("minute").toInstant().truncatedTo(ChronoUnit.MINUTES);
          String key = MINUTE.format(minute);
          long[] slot = byMinute.get(key);
          if (slot != null) {
            slot[0] = rs.getLong("orders");
            slot[1] = rs.getLong("revenue");
          }
        },
        Timestamp.from(start), Timestamp.from(end.plus(1, ChronoUnit.MINUTES)));

    List<Map<String, Object>> ordersPerMinute = new ArrayList<>();
    for (Map.Entry<String, long[]> e : byMinute.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("minute", e.getKey());
      row.put("orders", e.getValue()[0]);
      row.put("revenueCents", e.getValue()[1]);
      ordersPerMinute.add(row);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ordersTotal", ordersTotal == null ? 0L : ordersTotal);
    body.put("revenueCents", revenueCents == null ? 0L : revenueCents);
    body.put("revenueLast5mCents", revenueLast5m == null ? 0L : revenueLast5m);
    body.put("ordersPerMinute", ordersPerMinute);
    body.put("activeCustomers", activeCustomers == null ? 0L : activeCustomers);
    return body;
  }

  @Override
  public List<Map<String, Object>> topCustomers(int limit) {
    return jdbc.query(
        """
        SELECT customer_id, MAX(customer_name) AS customer_name,
               COUNT(*) AS orders, COALESCE(SUM(total_cents),0) AS total_cents
        FROM orders
        WHERE status = 'PLACED'
        GROUP BY customer_id
        ORDER BY total_cents DESC, customer_id ASC
        LIMIT ?
        """,
        (rs, i) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("customerId", rs.getString("customer_id"));
          row.put("customerName", rs.getString("customer_name"));
          row.put("orders", rs.getLong("orders"));
          row.put("totalCents", rs.getLong("total_cents"));
          return row;
        },
        limit);
  }

  private OrderRecord withItems(OrderRecord header) {
    List<OrderRecord.Item> items = jdbc.query(
        """
        SELECT sku, product_name, quantity, unit_price_cents
        FROM order_items WHERE order_id = ? ORDER BY line_no
        """,
        (rs, i) -> new OrderRecord.Item(
            rs.getString("sku"),
            rs.getString("product_name"),
            rs.getInt("quantity"),
            rs.getLong("unit_price_cents")),
        header.id());
    return new OrderRecord(
        header.id(),
        header.orderNumber(),
        header.customerId(),
        header.customerName(),
        header.status(),
        header.subtotalCents(),
        header.discountCents(),
        header.totalCents(),
        header.currency(),
        header.couponCode(),
        header.paymentId(),
        header.createdAt(),
        items);
  }

  private static OrderRecord mapHeader(long id, java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    return new OrderRecord(
        id,
        rs.getString("order_number"),
        rs.getString("customer_id"),
        rs.getString("customer_name"),
        rs.getString("status"),
        rs.getLong("subtotal_cents"),
        rs.getLong("discount_cents"),
        rs.getLong("total_cents"),
        rs.getString("currency"),
        rs.getString("coupon_code"),
        rs.getString("payment_id"),
        created == null ? Instant.now() : created.toInstant(),
        List.of());
  }
}
