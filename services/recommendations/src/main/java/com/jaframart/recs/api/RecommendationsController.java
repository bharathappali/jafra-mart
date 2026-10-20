package com.jaframart.recs.api;

import com.jaframart.recs.cache.SimilarityCache;
import com.jaframart.scenario.PodIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RecommendationsController {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final SimilarityCache cache;

  public RecommendationsController(SimilarityCache cache) {
    this.cache = cache;
  }

  @GetMapping("/recommendations")
  public ResponseEntity<?> recommendations(
      @RequestParam(required = false) String customerId,
      @RequestParam String productId,
      @RequestParam(required = false) String sessionId) {
    SimilarityCache.Recommendation result = cache.recommend(customerId, productId, sessionId);
    if (result == null) {
      return ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "unknown productId " + productId);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("productId", result.productId());
    body.put("items", Arrays.asList(result.items()));
    body.put("cache", result.cache());
    return ResponseEntity.ok(body);
  }

  @GetMapping("/recommendations/cache")
  public Map<String, Object> cacheStats() {
    return cache.cacheStats();
  }

  @GetMapping("/info")
  public Map<String, String> info() {
    return Map.of(
        "component", "recommendations",
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
