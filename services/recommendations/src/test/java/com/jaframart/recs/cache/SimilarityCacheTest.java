package com.jaframart.recs.cache;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SimilarityCacheTest {
  @Test
  void topEightIsDeterministic() {
    SimilarityCache cache = new SimilarityCache(384L * 1024 * 1024);
    String[] first = cache.topEight("customer007", "SKU-00042");
    String[] second = cache.topEight("customer007", "SKU-00042");
    assertEquals(8, first.length);
    assertArrayEquals(first, second);
    assertEquals(0, Arrays.stream(first).filter("SKU-00042"::equals).count());
  }

  @Test
  void lruEvictsAtThousand() {
    SimilarityCache cache = new SimilarityCache(384L * 1024 * 1024);
    for (int i = 1; i <= 1005; i++) {
      String customer = String.format("customer%03d", (i % 200) + 1);
      String sku = String.format("SKU-%05d", ((i - 1) % 20_000) + 1);
      SimilarityCache.Recommendation result = cache.recommend(customer, sku, null);
      assertNotNull(result);
      assertEquals("MISS", result.cache());
    }
    assertEquals(SimilarityCache.MAX_ENTRIES, cache.cacheEntries());
    assertEquals(SimilarityCache.MAX_ENTRIES * SimilarityEntry.ENTRY_BYTES, cache.cacheBytes());
  }
}
