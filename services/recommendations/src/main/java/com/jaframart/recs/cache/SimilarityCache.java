package com.jaframart.recs.cache;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class SimilarityCache {
  public static final int MAX_ENTRIES = 1000;
  public static final int TOP_N = 8;

  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, SimilarityEntry> map =
      new LinkedHashMap<>(16, 0.75f, true);
  private final Map<String, List<String>> byCategory;

  private long bytes;
  private double intensity;
  private boolean evictionEnabled = true;
  private long capBytes;
  private boolean growthFrozen;
  private final long maxHeap;

  public SimilarityCache() {
    this(Runtime.getRuntime().maxMemory());
  }

  SimilarityCache(long maxHeap) {
    this.maxHeap = maxHeap;
    this.byCategory = indexByCategory();
  }

  public Recommendation recommend(String customerId, String productId, String sessionId) {
    Product product = CatalogData.product(productId);
    if (product == null) {
      return null;
    }
    String customer = customerId == null ? "" : customerId;
    String session = sessionId == null ? "" : sessionId;
    lock.lock();
    try {
      String key = cacheKey(customer, productId, session);
      SimilarityEntry hit = map.get(key);
      if (hit != null) {
        return new Recommendation(productId, hit.skus(), "HIT");
      }
      SimilarityEntry entry = compute(key, customer, productId, product.category());
      if (!growthFrozen) {
        put(key, entry);
      }
      return new Recommendation(productId, entry.skus(), "MISS");
    } finally {
      lock.unlock();
    }
  }

  /** Deterministic top-8 for unit tests (no cache). */
  public String[] topEight(String customerId, String productId) {
    Product product = CatalogData.product(productId);
    if (product == null) {
      return new String[0];
    }
    return selectTop(customerId == null ? "" : customerId, productId, product.category());
  }

  SimilarityEntry compute(String key, String customerId, String productId, String category) {
    String[] skus = selectTop(customerId, productId, category);
    float[] embedding = embeddingFor(key);
    return new SimilarityEntry(skus, embedding);
  }

  public Map<String, Object> cacheStats() {
    lock.lock();
    try {
      return Map.of(
          "entries", map.size(),
          "bytes", bytes,
          "evictionEnabled", evictionEnabled,
          "maxEntries", MAX_ENTRIES);
    } finally {
      lock.unlock();
    }
  }

  public long cacheBytes() {
    lock.lock();
    try {
      return bytes;
    } finally {
      lock.unlock();
    }
  }

  public int cacheEntries() {
    lock.lock();
    try {
      return map.size();
    } finally {
      lock.unlock();
    }
  }

  public void setScenarioIntensity(double ci) {
    lock.lock();
    try {
      intensity = ci;
      if (ci > 0) {
        evictionEnabled = false;
        capBytes = Math.round(ci * 0.62 * maxHeap);
        trimToCap();
      } else {
        evictionEnabled = true;
        capBytes = 0;
        trimToMaxEntries();
      }
    } finally {
      lock.unlock();
    }
  }

  public void setGrowthFrozen(boolean frozen) {
    growthFrozen = frozen;
  }

  public void resetToBaseline() {
    lock.lock();
    try {
      intensity = 0;
      evictionEnabled = true;
      growthFrozen = false;
      capBytes = 0;
      map.clear();
      bytes = 0;
    } finally {
      lock.unlock();
    }
  }

  private String cacheKey(String customerId, String productId, String sessionId) {
    if (intensity > 0) {
      return customerId + "|" + productId + "|" + sessionId;
    }
    return customerId + "|" + productId;
  }

  private void put(String key, SimilarityEntry entry) {
    SimilarityEntry previous = map.put(key, entry);
    if (previous != null) {
      bytes -= SimilarityEntry.ENTRY_BYTES;
    }
    bytes += SimilarityEntry.ENTRY_BYTES;
    if (evictionEnabled) {
      trimToMaxEntries();
    } else {
      trimToCap();
    }
  }

  private void trimToMaxEntries() {
    while (map.size() > MAX_ENTRIES) {
      evictEldest();
    }
  }

  private void trimToCap() {
    while (capBytes > 0 && bytes > capBytes && !map.isEmpty()) {
      evictEldest();
    }
  }

  private void evictEldest() {
    Iterator<Map.Entry<String, SimilarityEntry>> it = map.entrySet().iterator();
    if (!it.hasNext()) {
      return;
    }
    it.next();
    it.remove();
    bytes -= SimilarityEntry.ENTRY_BYTES;
    if (bytes < 0) {
      bytes = 0;
    }
  }

  private String[] selectTop(String customerId, String productId, String category) {
    List<String> candidates = byCategory.getOrDefault(category, List.of());
    int customerHash = customerId.hashCode();
    List<Scored> scored = new ArrayList<>(candidates.size());
    for (String sku : candidates) {
      if (sku.equals(productId)) {
        continue;
      }
      int score = (customerHash ^ sku.hashCode()) & 0xFFFF;
      scored.add(new Scored(sku, score));
    }
    scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(Scored::sku));
    int n = Math.min(TOP_N, scored.size());
    String[] out = new String[n];
    for (int i = 0; i < n; i++) {
      out[i] = scored.get(i).sku();
    }
    return out;
  }

  static float[] embeddingFor(String key) {
    float[] embedding = new float[SimilarityEntry.EMBEDDING_DIM];
    SplittableRandom random = new SplittableRandom(Integer.toUnsignedLong(key.hashCode()));
    for (int i = 0; i < embedding.length; i++) {
      embedding[i] = random.nextFloat();
    }
    return embedding;
  }

  private static Map<String, List<String>> indexByCategory() {
    Map<String, List<String>> index = new HashMap<>();
    for (Product product : CatalogData.products()) {
      index.computeIfAbsent(product.category(), c -> new ArrayList<>()).add(product.sku());
    }
    Map<String, List<String>> frozen = new HashMap<>(index.size());
    for (Map.Entry<String, List<String>> entry : index.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(frozen);
  }

  public record Recommendation(String productId, String[] items, String cache) {}

  private record Scored(String sku, int score) {}
}
