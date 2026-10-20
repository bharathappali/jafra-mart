package com.jaframart.orders.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ClientsConfig {

  @Bean
  RestClient pricingRestClient(@Value("${PRICING_URL:http://pricing:8080}") String baseUrl) {
    return restClient(baseUrl, 1_000, 2_000);
  }

  @Bean
  RestClient inventoryRestClient(@Value("${INVENTORY_URL:http://inventory:8080}") String baseUrl) {
    return restClient(baseUrl, 1_000, 2_000);
  }

  @Bean
  RestClient paymentsRestClient(@Value("${PAYMENTS_URL:http://payments:8080}") String baseUrl) {
    return restClient(baseUrl, 1_000, 5_000);
  }

  @Bean
  RestClient reportsRestClient(@Value("${REPORTS_URL:http://reports-worker:8080}") String baseUrl) {
    return restClient(baseUrl, 1_000, 2_000);
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService invoiceDispatchExecutor() {
    AtomicInteger n = new AtomicInteger();
    ThreadFactory factory = r -> {
      Thread t = new Thread(r, "invoice-dispatch-" + n.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
    return Executors.newFixedThreadPool(2, factory);
  }

  @Bean
  PricingClient pricingClient(@Qualifier("pricingRestClient") RestClient pricingRestClient) {
    return new PricingClient(pricingRestClient);
  }

  @Bean
  InventoryClient inventoryClient(@Qualifier("inventoryRestClient") RestClient inventoryRestClient) {
    return new InventoryClient(inventoryRestClient);
  }

  @Bean
  PaymentsClient paymentsClient(@Qualifier("paymentsRestClient") RestClient paymentsRestClient) {
    return new PaymentsClient(paymentsRestClient);
  }

  @Bean
  ReportsClient reportsClient(
      @Qualifier("reportsRestClient") RestClient reportsRestClient,
      ExecutorService invoiceDispatchExecutor) {
    return new ReportsClient(reportsRestClient, invoiceDispatchExecutor);
  }

  private static RestClient restClient(String baseUrl, int connectMs, int readMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectMs));
    factory.setReadTimeout(Duration.ofMillis(readMs));
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .build();
  }
}
