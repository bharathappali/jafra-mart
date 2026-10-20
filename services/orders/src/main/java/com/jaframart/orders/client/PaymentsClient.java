package com.jaframart.orders.client;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.Map;

public final class PaymentsClient {
  private final RestClient client;

  public PaymentsClient(RestClient client) {
    this.client = client;
  }

  public Authorization authorize(String orderNumber, String customerId, long amountCents,
                                 String currency, String cardToken) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> resp = client.post()
          .uri("/api/payments/authorize")
          .body(Map.of(
              "orderNumber", orderNumber,
              "customerId", customerId == null ? "" : customerId,
              "amountCents", amountCents,
              "currency", currency == null ? "USD" : currency,
              "cardToken", cardToken == null ? "" : cardToken))
          .retrieve()
          .body(Map.class);
      if (resp == null) {
        throw new UpstreamTimeoutException("empty payment response");
      }
      return new Authorization(
          String.valueOf(resp.get("paymentId")),
          String.valueOf(resp.get("status")));
    } catch (ResourceAccessException ex) {
      throw new UpstreamTimeoutException(ex.getMessage(), ex);
    } catch (RestClientException ex) {
      if (isTimeout(ex)) {
        throw new UpstreamTimeoutException(ex.getMessage(), ex);
      }
      throw ex;
    }
  }

  private static boolean isTimeout(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof SocketTimeoutException) {
        return true;
      }
      if (t instanceof ResourceAccessException) {
        return true;
      }
    }
    return false;
  }

  public record Authorization(String paymentId, String status) {}

  public static final class UpstreamTimeoutException extends RuntimeException {
    public UpstreamTimeoutException(String message) {
      super(message);
    }

    public UpstreamTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
