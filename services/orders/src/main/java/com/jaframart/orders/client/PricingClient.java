package com.jaframart.orders.client;

import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public final class PricingClient {
  private final RestClient client;

  public PricingClient(RestClient client) {
    this.client = client;
  }

  @SuppressWarnings("unchecked")
  public Quote quote(String customerId, String couponCode, List<Map<String, Object>> items) {
    Map<String, Object> body = Map.of(
        "customerId", customerId == null ? "" : customerId,
        "couponCode", couponCode == null ? "" : couponCode,
        "items", items);
    Map<String, Object> resp = client.post()
        .uri("/api/quote")
        .body(body)
        .retrieve()
        .body(Map.class);
    if (resp == null) {
      throw new IllegalStateException("empty quote response");
    }
    List<Map<String, Object>> lines = (List<Map<String, Object>>) resp.get("lines");
    return new Quote(
        string(resp.get("currency"), "USD"),
        lines == null ? List.of() : lines,
        longVal(resp.get("subtotalCents")),
        longVal(resp.get("discountCents")),
        longVal(resp.get("totalCents")));
  }

  public record Quote(
      String currency,
      List<Map<String, Object>> lines,
      long subtotalCents,
      long discountCents,
      long totalCents) {}

  private static String string(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }

  private static long longVal(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    return value == null ? 0L : Long.parseLong(String.valueOf(value));
  }
}
