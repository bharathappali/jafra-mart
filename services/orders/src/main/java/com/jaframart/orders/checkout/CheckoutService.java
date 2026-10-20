package com.jaframart.orders.checkout;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Product;
import com.jaframart.orders.client.InventoryClient;
import com.jaframart.orders.client.PaymentsClient;
import com.jaframart.orders.client.PricingClient;
import com.jaframart.orders.client.ReportsClient;
import com.jaframart.orders.notify.FulfillmentNotifier;
import com.jaframart.orders.store.OrderRecord;
import com.jaframart.orders.store.OrderStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CheckoutService {
  private final OrderStore store;
  private final PricingClient pricing;
  private final InventoryClient inventory;
  private final PaymentsClient payments;
  private final ReportsClient reports;
  private final FulfillmentNotifier notifier;

  public CheckoutService(
      OrderStore store,
      PricingClient pricing,
      InventoryClient inventory,
      PaymentsClient payments,
      ReportsClient reports,
      FulfillmentNotifier notifier) {
    this.store = store;
    this.pricing = pricing;
    this.inventory = inventory;
    this.payments = payments;
    this.reports = reports;
    this.notifier = notifier;
  }

  public CheckoutResult place(PlaceRequest request) {
    if (request == null || request.items() == null || request.items().isEmpty()) {
      return CheckoutResult.badRequest("items required");
    }

    // (1) allocate order number
    String orderNumber = store.allocateOrderNumber();

    List<Map<String, Object>> quoteItems = new ArrayList<>();
    for (PlaceItem item : request.items()) {
      if (item.quantity() < 1) {
        return CheckoutResult.badRequest("quantity must be >= 1");
      }
      quoteItems.add(Map.of("sku", item.sku(), "quantity", item.quantity()));
    }

    // (2) quote
    PricingClient.Quote quote = pricing.quote(request.customerId(), request.couponCode(), quoteItems);
    List<OrderRecord.Item> lines = toLines(quote);

    // (3) reserve
    String reservationId;
    try {
      reservationId = inventory.reserve(orderNumber, quoteItems);
    } catch (InventoryClient.ConflictException ex) {
      persist(orderNumber, request, quote, lines, "FAILED", null);
      return CheckoutResult.conflict("insufficient stock");
    }

    // (4) authorize
    PaymentsClient.Authorization auth;
    try {
      auth = payments.authorize(
          orderNumber,
          request.customerId(),
          quote.totalCents(),
          quote.currency(),
          request.cardToken());
    } catch (PaymentsClient.UpstreamTimeoutException ex) {
      inventory.release(reservationId);
      persist(orderNumber, request, quote, lines, "FAILED", null);
      return CheckoutResult.upstreamTimeout("payment authorize timed out");
    }

    if ("DECLINED".equals(auth.status())) {
      inventory.release(reservationId);
      persist(orderNumber, request, quote, lines, "PAYMENT_DECLINED", auth.paymentId());
      return CheckoutResult.paymentDeclined("card declined");
    }

    // (5) insert order + items
    OrderRecord saved = persist(orderNumber, request, quote, lines, "PLACED", auth.paymentId());

    // (6) commit reservation
    inventory.commit(reservationId);

    Map<String, Object> response = toResponse(saved);

    // (7) fire-and-forget invoice
    reports.enqueueInvoiceAsync(response);

    // (8) fulfillment notify
    notifier.onOrderPlaced(saved);

    // (9) 201
    return CheckoutResult.placed(response);
  }

  private OrderRecord persist(
      String orderNumber,
      PlaceRequest request,
      PricingClient.Quote quote,
      List<OrderRecord.Item> lines,
      String status,
      String paymentId) {
    OrderRecord order = new OrderRecord(
        0L,
        orderNumber,
        request.customerId(),
        request.customerName() == null ? "" : request.customerName(),
        status,
        quote.subtotalCents(),
        quote.discountCents(),
        quote.totalCents(),
        quote.currency(),
        request.couponCode(),
        paymentId,
        Instant.now(),
        lines);
    return store.insert(order);
  }

  private static List<OrderRecord.Item> toLines(PricingClient.Quote quote) {
    List<OrderRecord.Item> lines = new ArrayList<>();
    for (Map<String, Object> line : quote.lines()) {
      String sku = String.valueOf(line.get("sku"));
      int quantity = ((Number) line.get("quantity")).intValue();
      long unitPrice = ((Number) line.get("unitPriceCents")).longValue();
      Product product = CatalogData.product(sku);
      String name = product == null ? sku : product.name();
      lines.add(new OrderRecord.Item(sku, name, quantity, unitPrice));
    }
    return lines;
  }

  public static Map<String, Object> toResponse(OrderRecord order) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderNumber", order.orderNumber());
    body.put("customerId", order.customerId());
    body.put("customerName", order.customerName());
    body.put("status", order.status());
    body.put("subtotalCents", order.subtotalCents());
    body.put("discountCents", order.discountCents());
    body.put("totalCents", order.totalCents());
    body.put("paymentId", order.paymentId());
    body.put("createdAt", formatInstant(order.createdAt()));
    List<Map<String, Object>> items = new ArrayList<>();
    for (OrderRecord.Item item : order.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sku", item.sku());
      row.put("productName", item.productName());
      row.put("quantity", item.quantity());
      row.put("unitPriceCents", item.unitPriceCents());
      items.add(row);
    }
    body.put("items", items);
    return body;
  }

  private static String formatInstant(Instant instant) {
    return DateTimes.format(instant);
  }

  public record PlaceRequest(
      String customerId,
      String customerName,
      String couponCode,
      String cardToken,
      List<PlaceItem> items) {}

  public record PlaceItem(String sku, int quantity) {}

  public record CheckoutResult(int status, Map<String, Object> body) {
    static CheckoutResult placed(Map<String, Object> body) {
      return new CheckoutResult(201, body);
    }

    static CheckoutResult conflict(String message) {
      return new CheckoutResult(409, Map.of("error", "CONFLICT", "message", message));
    }

    static CheckoutResult paymentDeclined(String message) {
      return new CheckoutResult(402, Map.of("error", "PAYMENT_DECLINED", "message", message));
    }

    static CheckoutResult upstreamTimeout(String message) {
      return new CheckoutResult(504, Map.of("error", "UPSTREAM_TIMEOUT", "message", message));
    }

    static CheckoutResult badRequest(String message) {
      return new CheckoutResult(400, Map.of("error", "BAD_REQUEST", "message", message));
    }
  }
}
