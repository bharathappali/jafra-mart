package com.jaframart.storefront.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BffMappingTest {
  private static HttpServer catalog;
  private static HttpServer inventory;
  private static HttpServer cart;
  private static HttpServer orders;
  private static HttpServer recommendations;
  private static HttpServer reports;
  private static HttpServer loadgen;
  private static final AtomicReference<String> lastCartPath = new AtomicReference<>();
  private static final AtomicReference<String> lastOrdersBody = new AtomicReference<>();

  @DynamicPropertySource
  static void urls(DynamicPropertyRegistry registry) {
    try {
      startServers();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    registry.add("CATALOG_URL", () -> url(catalog));
    registry.add("INVENTORY_URL", () -> url(inventory));
    registry.add("CART_URL", () -> url(cart));
    registry.add("ORDERS_URL", () -> url(orders));
    registry.add("RECOMMENDATIONS_URL", () -> url(recommendations));
    registry.add("REPORTS_URL", () -> url(reports));
    registry.add("LOADGEN_URL", () -> url(loadgen));
    registry.add("STOREFRONT_URL", () -> "http://127.0.0.1:9");
    registry.add("SERVICE_URLS", () -> "catalog-search=" + url(catalog));
  }

  private static synchronized void startServers() throws IOException {
    if (catalog != null) {
      return;
    }
    catalog = HttpServer.create(new InetSocketAddress(0), 0);
    inventory = HttpServer.create(new InetSocketAddress(0), 0);
    cart = HttpServer.create(new InetSocketAddress(0), 0);
    orders = HttpServer.create(new InetSocketAddress(0), 0);
    recommendations = HttpServer.create(new InetSocketAddress(0), 0);
    reports = HttpServer.create(new InetSocketAddress(0), 0);
    loadgen = HttpServer.create(new InetSocketAddress(0), 0);

    catalog.createContext("/api/products", ex -> {
      byte[] body;
      if (ex.getRequestURI().getPath().endsWith("/SKU-00042")) {
        body = "{\"sku\":\"SKU-00042\",\"name\":\"Frosted Glass Smart Lamp\",\"priceCents\":12999,\"rating\":4.8,\"category\":\"Home\"}".getBytes(StandardCharsets.UTF_8);
      } else {
        body = "{\"page\":0,\"size\":24,\"total\":1,\"items\":[{\"sku\":\"SKU-00042\",\"name\":\"Frosted Glass Smart Lamp\",\"priceCents\":12999,\"rating\":4.8}]}".getBytes(StandardCharsets.UTF_8);
      }
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    inventory.createContext("/api/inventory/SKU-00042", ex -> {
      byte[] body = "{\"sku\":\"SKU-00042\",\"available\":50}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    cart.createContext("/api/carts", ex -> {
      lastCartPath.set(ex.getRequestURI().getPath());
      if ("DELETE".equals(ex.getRequestMethod())) {
        ex.sendResponseHeaders(204, -1);
        ex.close();
        return;
      }
      byte[] body = "{\"customerId\":\"user\",\"items\":[{\"sku\":\"SKU-00042\",\"quantity\":1}],\"couponCode\":null,\"subtotalCents\":12999}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    orders.createContext("/api/orders", ex -> {
      lastOrdersBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = "{\"orderNumber\":\"JM-20261019-000001\",\"status\":\"PLACED\",\"subtotalCents\":12999,\"discountCents\":0,\"totalCents\":12999,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(201, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    orders.createContext("/api/admin/summary", ex -> {
      byte[] body = "{\"ordersTotal\":1,\"revenueCents\":100,\"revenueLast5mCents\":100,\"ordersPerMinute\":[],\"activeCustomers\":1}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    recommendations.createContext("/api/recommendations", ex -> {
      byte[] body = "{\"productId\":\"SKU-00042\",\"items\":[\"SKU-00001\"],\"cache\":\"MISS\"}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    reports.createContext("/", ex -> {
      ex.sendResponseHeaders(404, -1);
      ex.close();
    });
    loadgen.createContext("/api/scenarios", ex -> {
      byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });

    catalog.start();
    inventory.start();
    cart.start();
    orders.start();
    recommendations.start();
    reports.start();
    loadgen.start();
  }

  @BeforeEach
  void clear() {
    lastCartPath.set(null);
    lastOrdersBody.set(null);
  }

  @Autowired
  MockMvc mockMvc;

  @Test
  void catalogProductsProxied() throws Exception {
    MockHttpSession session = login("user", "user");
    mockMvc.perform(get("/api/catalog/products").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sku").value("SKU-00042"));
  }

  @Test
  void productDetailCombinesCatalogAndInventory() throws Exception {
    MockHttpSession session = login("user", "user");
    mockMvc.perform(get("/api/catalog/products/SKU-00042").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product.sku").value("SKU-00042"))
        .andExpect(jsonPath("$.availability.available").value(50));
  }

  @Test
  void cartPathUsesCustomerId() throws Exception {
    MockHttpSession session = login("user", "user");
    mockMvc.perform(post("/api/cart/items")
            .session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sku\":\"SKU-00042\",\"quantity\":1}"))
        .andExpect(status().isOk());
    assertTrue(lastCartPath.get().contains("/api/carts/user/items"));
  }

  @Test
  void checkoutPassesThroughOrdersStatus() throws Exception {
    MockHttpSession session = login("user", "user");
    mockMvc.perform(post("/api/checkout")
            .session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"cardToken\":\"tok_visa\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PLACED"));
    assertTrue(lastOrdersBody.get().contains("SKU-00042"));
    assertTrue(lastOrdersBody.get().contains("Demo User"));
  }

  @Test
  void conductorProxyMapsPath() throws Exception {
    MockHttpSession session = login("admin", "admin");
    mockMvc.perform(get("/api/admin/conductor/scenarios").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void timeoutErrorShapeIs504() {
    ResponseEntity<byte[]> error = Downstream.errorJson(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "timed out");
    assertTrue(error.getStatusCode().value() == 504);
    assertTrue(new String(error.getBody(), StandardCharsets.UTF_8).contains("UPSTREAM_TIMEOUT"));
  }

  private MockHttpSession login(String user, String pass) throws Exception {
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return (MockHttpSession) login.getRequest().getSession();
  }

  private static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }
}
