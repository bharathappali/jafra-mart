package com.jaframart.storefront.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class ClientsConfig {

  @Bean
  RestClient catalogRestClient(@Value("${CATALOG_URL:http://catalog-search:8080}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  RestClient inventoryRestClient(@Value("${INVENTORY_URL:http://inventory:8080}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  RestClient cartRestClient(@Value("${CART_URL:http://cart:8080}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  RestClient ordersRestClient(@Value("${ORDERS_URL:http://orders:8080}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  RestClient recommendationsRestClient(
      @Value("${RECOMMENDATIONS_URL:http://recommendations:8080}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  RestClient reportsRestClient(@Value("${REPORTS_URL:http://reports-worker:8080}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  RestClient loadgenRestClient(@Value("${LOADGEN_URL:http://loadgen:8080}") String baseUrl) {
    return restClient(baseUrl);
  }

  @Bean
  Map<String, RestClient> serviceInfoClients(
      @Value("${SERVICE_URLS:}") String serviceUrls,
      @Value("${STOREFRONT_URL:http://storefront:8080}") String storefrontUrl,
      @Value("${LOADGEN_URL:http://loadgen:8080}") String loadgenUrl) {
    Map<String, RestClient> clients = new LinkedHashMap<>();
    if (serviceUrls != null && !serviceUrls.isBlank()) {
      for (String part : serviceUrls.split(",")) {
        String[] kv = part.split("=", 2);
        if (kv.length == 2) {
          clients.put(kv[0].trim(), restClient(kv[1].trim()));
        }
      }
    }
    clients.putIfAbsent("storefront", restClient(storefrontUrl));
    clients.putIfAbsent("loadgen", restClient(loadgenUrl));
    return clients;
  }

  static RestClient restClient(String baseUrl) {
    return restClient(baseUrl, 1_000, 10_000);
  }

  static RestClient restClient(String baseUrl, int connectMs, int readMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectMs));
    factory.setReadTimeout(Duration.ofMillis(readMs));
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .build();
  }
}
