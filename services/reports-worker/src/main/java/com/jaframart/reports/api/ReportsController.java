package com.jaframart.reports.api;

import com.jaframart.reports.export.ExportService;
import com.jaframart.reports.invoice.InvoiceWriter;
import com.jaframart.reports.invoice.OrderInvoice;
import com.jaframart.reports.reconcile.ReconciliationJob;
import com.jaframart.scenario.PodIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReportsController {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final InvoiceWriter invoices;
  private final ExportService exports;
  private final ReconciliationJob reconciliation;

  public ReportsController(InvoiceWriter invoices, ExportService exports, ReconciliationJob reconciliation) {
    this.invoices = invoices;
    this.exports = exports;
    this.reconciliation = reconciliation;
  }

  @PostMapping("/invoices")
  public ResponseEntity<?> enqueue(@RequestBody Map<String, Object> body) {
    try {
      OrderInvoice order = OrderInvoice.fromMap(body);
      invoices.enqueue(order);
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
          "orderNumber", order.orderNumber(),
          "queued", true));
    } catch (IllegalArgumentException ex) {
      return ApiError.of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }
  }

  @GetMapping("/invoices/{orderNumber}")
  public ResponseEntity<?> getInvoice(@PathVariable String orderNumber) throws Exception {
    String text = invoices.readText(orderNumber);
    if (text == null) {
      for (int i = 0; i < 50 && text == null; i++) {
        Thread.sleep(20);
        text = invoices.readText(orderNumber);
      }
    }
    if (text == null) {
      return ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "invoice " + orderNumber);
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(text);
  }

  @PostMapping("/invoices/reprint")
  public ResponseEntity<?> reprint(@RequestBody Map<String, Object> body) {
    try {
      int count = body == null || body.get("count") == null ? 0 : ((Number) body.get("count")).intValue();
      return ResponseEntity.ok(invoices.reprint(count));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return ApiError.of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    } catch (Exception ex) {
      return ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", ex.getMessage());
    }
  }

  @GetMapping("/reports/exports/orders")
  public ResponseEntity<?> exportOrders(@RequestParam("rows") int rows) {
    try {
      return ResponseEntity.ok(exports.exportOrders(rows));
    } catch (IllegalArgumentException ex) {
      return ApiError.of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    } catch (ExportService.ExportBusyException ex) {
      return ApiError.of(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", ex.getMessage());
    } catch (Exception ex) {
      return ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", ex.getMessage());
    }
  }

  @GetMapping("/reports/daily")
  public Map<String, Object> daily() {
    String last = reconciliation.lastReconciliation();
    return Map.of(
        "invoices", invoices.invoicesWritten(),
        "exports", exports.exportsCompleted(),
        "lastReconciliation", last == null ? "" : last);
  }

  @GetMapping("/info")
  public Map<String, String> info() {
    return Map.of(
        "component", "reports-worker",
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
}
