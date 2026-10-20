package com.jaframart.orders.client;

import org.springframework.web.client.RestClient;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public final class ReportsClient {
  private static final Logger LOG = System.getLogger(ReportsClient.class.getName());

  private final RestClient client;
  private final ExecutorService executor;

  public ReportsClient(RestClient client, ExecutorService executor) {
    this.client = client;
    this.executor = executor;
  }

  public void enqueueInvoiceAsync(Map<String, Object> orderJson) {
    executor.execute(() -> {
      try {
        client.post()
            .uri("/api/invoices")
            .body(orderJson)
            .retrieve()
            .toBodilessEntity();
      } catch (RuntimeException ex) {
        LOG.log(Level.WARNING, "invoice dispatch failed for " + orderJson.get("orderNumber"), ex);
      }
    });
  }
}
