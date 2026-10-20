package com.jaframart.orders.checkout;

import com.jaframart.orders.client.InventoryClient;
import com.jaframart.orders.client.PaymentsClient;
import com.jaframart.orders.client.PricingClient;
import com.jaframart.orders.client.ReportsClient;
import com.jaframart.orders.notify.FulfillmentNotifier;
import com.jaframart.orders.store.InMemoryOrderStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutServiceTest {
  private HttpServer pricing;
  private HttpServer inventory;
  private HttpServer payments;
  private HttpServer reports;
  private InMemoryOrderStore store;
  private CheckoutService checkout;
  private final AtomicInteger invoiceCalls = new AtomicInteger();

  @BeforeEach
  void setUp() throws IOException {
    pricing = HttpServer.create(new InetSocketAddress(0), 0);
    inventory = HttpServer.create(new InetSocketAddress(0), 0);
    payments = HttpServer.create(new InetSocketAddress(0), 0);
    reports = HttpServer.create(new InetSocketAddress(0), 0);
    store = new InMemoryOrderStore();
    invoiceCalls.set(0);

    pricing.createContext("/api/quote", ex -> {
      byte[] body = """
          {"currency":"USD","lines":[{"sku":"SKU-00042","quantity":2,"unitPriceCents":12999,"lineTotalCents":25998,"appliedRules":["base"]}],"subtotalCents":25998,"discountCents":2600,"totalCents":23398,"campaign":"none"}
          """.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    inventory.createContext("/api/reservations", ex -> {
      String path = ex.getRequestURI().getPath();
      if (path.endsWith("/commit") || path.endsWith("/release")) {
        byte[] body = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
          os.write(body);
        }
        return;
      }
      byte[] body = "{\"reservationId\":\"R-1\",\"status\":\"HELD\",\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}"
          .getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(201, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    reports.createContext("/api/invoices", ex -> {
      invoiceCalls.incrementAndGet();
      byte[] body = "{\"queued\":true}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(202, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });

    pricing.start();
    inventory.start();
    payments.start();
    reports.start();
  }

  @AfterEach
  void tearDown() {
    pricing.stop(0);
    inventory.stop(0);
    payments.stop(0);
    reports.stop(0);
  }

  @Test
  void placedReturns201WithTotals() throws Exception {
    payments.createContext("/api/payments/authorize", ex -> {
      byte[] body = "{\"paymentId\":\"P-1\",\"status\":\"AUTHORIZED\",\"riskScore\":1,\"latencyMs\":1}"
          .getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    CheckoutService svc = build();
    CheckoutService.CheckoutResult result = svc.place(request("tok_visa"));
    assertEquals(201, result.status());
    assertEquals("PLACED", result.body().get("status"));
    assertEquals(25998L, ((Number) result.body().get("subtotalCents")).longValue());
    assertEquals(2600L, ((Number) result.body().get("discountCents")).longValue());
    assertEquals(23398L, ((Number) result.body().get("totalCents")).longValue());
    assertTrue(result.body().get("orderNumber").toString().matches("^JM-\\d{8}-\\d{6}$"));
    Thread.sleep(50);
    assertTrue(invoiceCalls.get() >= 1);
  }

  @Test
  void inventoryConflictReturns409() {
    inventory.removeContext("/api/reservations");
    inventory.createContext("/api/reservations", ex -> {
      byte[] body = "{\"error\":\"CONFLICT\",\"message\":\"insufficient stock\"}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(409, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    payments.createContext("/api/payments/authorize", ex -> ex.sendResponseHeaders(500, -1));
    CheckoutService.CheckoutResult result = build().place(request("tok_visa"));
    assertEquals(409, result.status());
    assertEquals("CONFLICT", result.body().get("error"));
    assertEquals("FAILED", store.findByNumber(store.listByCustomer("user", 1).getFirst().orderNumber()).orElseThrow().status());
  }

  @Test
  void declinedReturns402() {
    payments.createContext("/api/payments/authorize", ex -> {
      byte[] body = "{\"paymentId\":\"P-2\",\"status\":\"DECLINED\",\"riskScore\":99,\"latencyMs\":1}"
          .getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    CheckoutService.CheckoutResult result = build().place(request("tok_decline"));
    assertEquals(402, result.status());
    assertEquals("PAYMENT_DECLINED", result.body().get("error"));
    assertEquals("PAYMENT_DECLINED",
        store.listByCustomer("user", 1).getFirst().status());
  }

  @Test
  void paymentTimeoutReturns504() {
    payments.createContext("/api/payments/authorize", ex -> {
      try {
        Thread.sleep(3_500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ex.sendResponseHeaders(200, -1);
    });
    // use short read timeout for this test only
    CheckoutService svc = buildWithPaymentTimeout(500);
    CheckoutService.CheckoutResult result = svc.place(request("tok_visa"));
    assertEquals(504, result.status());
    assertEquals("UPSTREAM_TIMEOUT", result.body().get("error"));
    assertEquals("FAILED", store.listByCustomer("user", 1).getFirst().status());
  }

  private CheckoutService.PlaceRequest request(String cardToken) {
    return new CheckoutService.PlaceRequest(
        "user",
        "Demo User",
        "WELCOME10",
        cardToken,
        List.of(new CheckoutService.PlaceItem("SKU-00042", 2)));
  }

  private CheckoutService build() {
    return buildWithPaymentTimeout(5_000);
  }

  private CheckoutService buildWithPaymentTimeout(int paymentReadMs) {
    PricingClient pricingClient = new PricingClient(client(pricing.getAddress().getPort(), 2_000));
    InventoryClient inventoryClient = new InventoryClient(client(inventory.getAddress().getPort(), 2_000));
    PaymentsClient paymentsClient = new PaymentsClient(client(payments.getAddress().getPort(), paymentReadMs));
    ReportsClient reportsClient = new ReportsClient(
        client(reports.getAddress().getPort(), 2_000),
        Executors.newFixedThreadPool(2));
    return new CheckoutService(
        store, pricingClient, inventoryClient, paymentsClient, reportsClient, new FulfillmentNotifier());
  }

  private static RestClient client(int port, int readMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(1_000));
    factory.setReadTimeout(Duration.ofMillis(readMs));
    return RestClient.builder()
        .baseUrl("http://127.0.0.1:" + port)
        .requestFactory(factory)
        .build();
  }
}
