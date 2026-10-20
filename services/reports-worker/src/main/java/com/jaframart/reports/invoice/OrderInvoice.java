package com.jaframart.reports.invoice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OrderInvoice {
  private final String orderNumber;
  private final String customerId;
  private final String customerName;
  private final String status;
  private final long subtotalCents;
  private final long discountCents;
  private final long totalCents;
  private final String paymentId;
  private final String createdAt;
  private final List<Line> items;

  public OrderInvoice(String orderNumber, String customerId, String customerName, String status,
                      long subtotalCents, long discountCents, long totalCents, String paymentId,
                      String createdAt, List<Line> items) {
    this.orderNumber = orderNumber;
    this.customerId = customerId;
    this.customerName = customerName;
    this.status = status;
    this.subtotalCents = subtotalCents;
    this.discountCents = discountCents;
    this.totalCents = totalCents;
    this.paymentId = paymentId;
    this.createdAt = createdAt;
    this.items = List.copyOf(items);
  }

  @SuppressWarnings("unchecked")
  public static OrderInvoice fromMap(Map<String, Object> raw) {
    if (raw == null || raw.get("orderNumber") == null) {
      throw new IllegalArgumentException("orderNumber required");
    }
    List<Line> lines = new ArrayList<>();
    Object itemsObj = raw.get("items");
    if (itemsObj instanceof List<?> list) {
      for (Object entry : list) {
        if (entry instanceof Map<?, ?> m) {
          lines.add(new Line(
              string(m.get("sku")),
              string(m.get("productName")),
              intVal(m.get("quantity")),
              longVal(m.get("unitPriceCents"))));
        }
      }
    }
    return new OrderInvoice(
        string(raw.get("orderNumber")),
        string(raw.get("customerId")),
        string(raw.get("customerName")),
        string(raw.getOrDefault("status", "PLACED")),
        longVal(raw.get("subtotalCents")),
        longVal(raw.get("discountCents")),
        longVal(raw.get("totalCents")),
        string(raw.get("paymentId")),
        string(raw.get("createdAt")),
        lines);
  }

  public Map<String, Object> toProjection() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("orderNumber", orderNumber);
    map.put("customerId", customerId);
    map.put("customerName", customerName);
    map.put("status", status);
    map.put("subtotalCents", subtotalCents);
    map.put("discountCents", discountCents);
    map.put("totalCents", totalCents);
    map.put("paymentId", paymentId);
    map.put("createdAt", createdAt);
    List<Map<String, Object>> itemMaps = new ArrayList<>();
    for (Line line : items) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("sku", line.sku());
      item.put("productName", line.productName());
      item.put("quantity", line.quantity());
      item.put("unitPriceCents", line.unitPriceCents());
      itemMaps.add(item);
    }
    map.put("items", itemMaps);
    return map;
  }

  public String orderNumber() { return orderNumber; }
  public String customerId() { return customerId; }
  public String customerName() { return customerName; }
  public String status() { return status; }
  public long subtotalCents() { return subtotalCents; }
  public long discountCents() { return discountCents; }
  public long totalCents() { return totalCents; }
  public String paymentId() { return paymentId; }
  public String createdAt() { return createdAt; }
  public List<Line> items() { return items; }

  public record Line(String sku, String productName, int quantity, long unitPriceCents) {}

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static int intVal(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value == null) {
      return 0;
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private static long longVal(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value == null) {
      return 0L;
    }
    return Long.parseLong(String.valueOf(value));
  }
}
