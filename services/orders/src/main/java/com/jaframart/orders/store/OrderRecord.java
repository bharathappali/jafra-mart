package com.jaframart.orders.store;

import java.time.Instant;
import java.util.List;

public record OrderRecord(
    long id,
    String orderNumber,
    String customerId,
    String customerName,
    String status,
    long subtotalCents,
    long discountCents,
    long totalCents,
    String currency,
    String couponCode,
    String paymentId,
    Instant createdAt,
    List<Item> items) {

  public record Item(String sku, String productName, int quantity, long unitPriceCents) {}
}
