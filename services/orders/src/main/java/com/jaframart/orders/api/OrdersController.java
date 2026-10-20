package com.jaframart.orders.api;

import com.jaframart.orders.checkout.CheckoutService;
import com.jaframart.orders.checkout.DateTimes;
import com.jaframart.orders.store.OrderStore;
import com.jaframart.scenario.PodIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrdersController {
  private static final Instant STARTED = Instant.now();

  private final CheckoutService checkout;
  private final OrderStore store;

  public OrdersController(CheckoutService checkout, OrderStore store) {
    this.checkout = checkout;
    this.store = store;
  }

  @PostMapping("/orders")
  public ResponseEntity<?> place(@RequestBody CheckoutService.PlaceRequest request) {
    CheckoutService.CheckoutResult result = checkout.place(request);
    return ResponseEntity.status(result.status()).body(result.body());
  }

  @GetMapping("/orders/{orderNumber}")
  public ResponseEntity<?> get(@PathVariable String orderNumber) {
    return store.findByNumber(orderNumber)
        .<ResponseEntity<?>>map(o -> ResponseEntity.ok(CheckoutService.toResponse(o)))
        .orElseGet(() -> ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "order " + orderNumber));
  }

  @GetMapping("/orders")
  public ResponseEntity<?> list(
      @RequestParam String customerId,
      @RequestParam(defaultValue = "20") int limit) {
    int capped = Math.max(1, Math.min(limit, 200));
    List<Map<String, Object>> body = store.listByCustomer(customerId, capped).stream()
        .map(CheckoutService::toResponse)
        .toList();
    return ResponseEntity.ok(body);
  }

  @GetMapping("/admin/summary")
  public Map<String, Object> summary() {
    return store.summary();
  }

  @GetMapping("/admin/orders/recent")
  public ResponseEntity<?> recent(@RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 200) {
      return ApiError.of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "limit must be 1..200");
    }
    List<Map<String, Object>> body = store.recent(limit).stream()
        .map(CheckoutService::toResponse)
        .toList();
    return ResponseEntity.ok(body);
  }

  @GetMapping("/admin/customers/top")
  public List<Map<String, Object>> top(@RequestParam(defaultValue = "10") int limit) {
    int capped = Math.max(1, Math.min(limit, 100));
    return store.topCustomers(capped);
  }

  @GetMapping("/info")
  public Map<String, String> info() {
    return Map.of(
        "component", "orders",
        "framework", "spring-boot 4.1.1",
        "gc", gc(),
        "java", System.getProperty("java.version"),
        "pod", PodIdentity.pod(),
        "startedAt", DateTimes.format(STARTED));
  }

  static String gc() {
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = bean.getName();
      if (name.startsWith("G1")) {
        return "G1";
      }
      if (name.startsWith("PS")) {
        return "Parallel";
      }
      if (name.startsWith("ZGC")) {
        return "ZGC";
      }
      if (name.startsWith("Shenandoah")) {
        return "Shenandoah";
      }
      if ("Copy".equals(name) || "MarkSweepCompact".equals(name)) {
        return "Serial";
      }
    }
    return "unknown";
  }
}
