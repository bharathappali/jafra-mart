package com.jaframart.loadgen;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HttpClients {
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  HttpClient plain() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .executor(executor)
        .build();
  }

  Session session() {
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .cookieHandler(cookies)
        .executor(executor)
        .build();
    return new Session(client, cookies);
  }

  void close() {
    executor.shutdownNow();
  }

  record Session(HttpClient client, CookieManager cookies) {
    HttpResponse<byte[]> send(HttpRequest request) throws Exception {
      return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  static HttpRequest.Builder request(URI uri) {
    return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
  }
}
