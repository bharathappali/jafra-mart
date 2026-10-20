package com.jaframart.orders.client;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public final class InventoryClient {
  private final RestClient client;

  public InventoryClient(RestClient client) {
    this.client = client;
  }

  public String reserve(String orderNumber, List<Map<String, Object>> items) {
    return client.post()
        .uri("/api/reservations")
        .body(Map.of("orderNumber", orderNumber, "items", items))
        .exchange((req, res) -> {
          if (res.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
            throw new ConflictException("insufficient stock");
          }
          if (!res.getStatusCode().is2xxSuccessful()) {
            throw new UpstreamException("inventory reserve failed: " + res.getStatusCode());
          }
          @SuppressWarnings("unchecked")
          Map<String, Object> body = res.bodyTo(Map.class);
          if (body == null || body.get("reservationId") == null) {
            throw new UpstreamException("inventory reserve missing reservationId");
          }
          return String.valueOf(body.get("reservationId"));
        });
  }

  public void commit(String reservationId) {
    client.post()
        .uri("/api/reservations/{id}/commit", reservationId)
        .retrieve()
        .toBodilessEntity();
  }

  public void release(String reservationId) {
    try {
      client.post()
          .uri("/api/reservations/{id}/release", reservationId)
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException ignored) {
      // best-effort release on payment failure paths
    }
  }

  public static final class ConflictException extends RuntimeException {
    public ConflictException(String message) {
      super(message);
    }
  }

  public static final class UpstreamException extends RuntimeException {
    public UpstreamException(String message) {
      super(message);
    }
  }
}
