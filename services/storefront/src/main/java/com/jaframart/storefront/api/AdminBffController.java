package com.jaframart.storefront.api;

import com.jaframart.storefront.client.Downstream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin")
public class AdminBffController {
  private final RestClient orders;
  private final RestClient reports;
  private final Map<String, RestClient> serviceInfoClients;
  private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "admin-bff");
    t.setDaemon(true);
    return t;
  });

  public AdminBffController(
      @Qualifier("ordersRestClient") RestClient orders,
      @Qualifier("reportsRestClient") RestClient reports,
      Map<String, RestClient> serviceInfoClients) {
    this.orders = orders;
    this.reports = reports;
    this.serviceInfoClients = serviceInfoClients;
  }

  @GetMapping("/summary")
  public ResponseEntity<byte[]> summary() {
    return Downstream.get(orders, "/api/admin/summary");
  }

  @GetMapping("/orders/recent")
  public ResponseEntity<byte[]> recent(@RequestParam(required = false) Integer limit) {
    UriComponentsBuilder b = UriComponentsBuilder.fromPath("/api/admin/orders/recent");
    if (limit != null) {
      b.queryParam("limit", limit);
    }
    return Downstream.get(orders, b.build(true).toUriString());
  }

  @GetMapping("/customers/top")
  public ResponseEntity<byte[]> top(@RequestParam(required = false) Integer limit) {
    UriComponentsBuilder b = UriComponentsBuilder.fromPath("/api/admin/customers/top");
    if (limit != null) {
      b.queryParam("limit", limit);
    }
    return Downstream.get(orders, b.build(true).toUriString());
  }

  @GetMapping("/customers/{customerId}/orders")
  public ResponseEntity<byte[]> customerOrders(@PathVariable String customerId) {
    String uri = UriComponentsBuilder.fromPath("/api/orders")
        .queryParam("customerId", customerId)
        .queryParam("limit", 50)
        .build(true)
        .toUriString();
    return Downstream.get(orders, uri);
  }

  @GetMapping("/reports/export")
  public ResponseEntity<byte[]> export(@RequestParam(required = false) Integer rows) {
    UriComponentsBuilder b = UriComponentsBuilder.fromPath("/api/reports/exports/orders");
    if (rows != null) {
      b.queryParam("rows", rows);
    }
    return Downstream.get(reports, b.build(true).toUriString());
  }

  @PostMapping("/reports/reprint")
  public ResponseEntity<byte[]> reprint(@RequestBody Map<String, Object> body) {
    return Downstream.post(reports, "/api/invoices/reprint", body);
  }

  @GetMapping("/reports/daily")
  public ResponseEntity<byte[]> daily() {
    return Downstream.get(reports, "/api/reports/daily");
  }

  @GetMapping("/services")
  public List<Map<String, Object>> services() {
    List<Future<Map<String, Object>>> futures = new ArrayList<>();
    for (Map.Entry<String, RestClient> entry : serviceInfoClients.entrySet()) {
      String name = entry.getKey();
      RestClient client = entry.getValue();
      futures.add(pool.submit(() -> probe(name, client)));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < futures.size(); i++) {
      String name = new ArrayList<>(serviceInfoClients.keySet()).get(i);
      try {
        result.add(futures.get(i).get(2, TimeUnit.SECONDS));
      } catch (Exception ex) {
        result.add(down(name));
      }
    }
    return result;
  }

  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L);
    Future<?> task = pool.submit(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          ResponseEntity<byte[]> summary = Downstream.get(orders, "/api/admin/summary");
          ResponseEntity<byte[]> recent = Downstream.get(orders, "/api/admin/orders/recent?limit=20");
          emitter.send(SseEmitter.event().name("summary").data(bodyOrEmpty(summary), MediaType.APPLICATION_JSON));
          emitter.send(SseEmitter.event().name("orders").data(bodyOrEmpty(recent), MediaType.APPLICATION_JSON));
          Thread.sleep(2_000);
        }
      } catch (IOException | InterruptedException ex) {
        emitter.complete();
        Thread.currentThread().interrupt();
      } catch (Exception ex) {
        emitter.completeWithError(ex);
      }
    });
    emitter.onCompletion(() -> task.cancel(true));
    emitter.onTimeout(() -> task.cancel(true));
    return emitter;
  }

  private static Map<String, Object> probe(String component, RestClient client) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> info = Downstream.getJson(client, "/api/info", Map.class);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("component", component);
      row.put("up", true);
      row.put("pod", String.valueOf(info.getOrDefault("pod", "")));
      row.put("gc", String.valueOf(info.getOrDefault("gc", "")));
      row.put("framework", String.valueOf(info.getOrDefault("framework", "")));
      return row;
    } catch (Exception ex) {
      return down(component);
    }
  }

  private static Map<String, Object> down(String component) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("component", component);
    row.put("up", false);
    row.put("pod", "");
    row.put("gc", "");
    row.put("framework", "");
    return row;
  }

  private static String bodyOrEmpty(ResponseEntity<byte[]> entity) {
    byte[] body = entity.getBody();
    if (body == null) {
      return "{}";
    }
    return new String(body, StandardCharsets.UTF_8);
  }
}
