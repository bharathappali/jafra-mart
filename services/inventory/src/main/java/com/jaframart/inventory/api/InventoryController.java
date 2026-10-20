package com.jaframart.inventory.api;

import com.jaframart.inventory.stock.StockLedger;
import com.jaframart.scenario.PodIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InventoryController {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final StockLedger ledger;

  public InventoryController(StockLedger ledger) {
    this.ledger = ledger;
  }

  @GetMapping("/inventory/{sku}")
  public ResponseEntity<?> availability(@PathVariable String sku) {
    Map<String, Object> body = ledger.availability(sku);
    if (body == null) {
      return ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "unknown sku " + sku);
    }
    return ResponseEntity.ok(body);
  }

  @PostMapping("/reservations")
  public ResponseEntity<?> reserve(@RequestBody ReserveRequest request) {
    if (request == null || request.items() == null || request.items().isEmpty()) {
      return ApiError.of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "items required");
    }
    try {
      List<StockLedger.Line> lines = new ArrayList<>();
      for (ReserveItem item : request.items()) {
        lines.add(new StockLedger.Line(item.sku(), item.quantity()));
      }
      StockLedger.Reservation reservation = ledger.reserve(request.orderNumber(), lines);
      if (reservation == null) {
        return ApiError.of(HttpStatus.CONFLICT, "CONFLICT", "insufficient stock");
      }
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
          "reservationId", reservation.id(),
          "status", reservation.status(),
          "expiresAt", ISO.format(reservation.expiresAt())));
    } catch (IllegalArgumentException ex) {
      return ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }
  }

  @PostMapping("/reservations/{id}/commit")
  public ResponseEntity<?> commit(@PathVariable String id) {
    StockLedger.Reservation reservation = ledger.commit(id);
    if (reservation == null) {
      return ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "unknown reservation " + id);
    }
    return ResponseEntity.ok(Map.of("reservationId", reservation.id(), "status", reservation.status()));
  }

  @PostMapping("/reservations/{id}/release")
  public ResponseEntity<?> release(@PathVariable String id) {
    StockLedger.Reservation reservation = ledger.release(id);
    if (reservation == null) {
      return ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "unknown reservation " + id);
    }
    return ResponseEntity.ok(Map.of("reservationId", reservation.id(), "status", reservation.status()));
  }

  @GetMapping("/info")
  public Map<String, String> info() {
    return Map.of(
        "component", "inventory",
        "framework", "spring-boot 4.1.1",
        "gc", gc(),
        "java", System.getProperty("java.version"),
        "pod", PodIdentity.pod(),
        "startedAt", ISO.format(STARTED));
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

  public record ReserveRequest(String orderNumber, List<ReserveItem> items) {}

  public record ReserveItem(String sku, int quantity) {}
}
