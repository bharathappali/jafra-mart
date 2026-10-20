package com.jaframart.catalog.search;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {
  @Test
  void exactLampIsOrderedBySku() {
    SearchService search = new SearchService();
    List<Product> found = search.exact("lamp", 20);
    assertEquals(20, found.size());
    List<String> skus = new ArrayList<>();
    for (Product product : found) {
      assertTrue(product.name().toLowerCase().contains("lamp") || product.tags().stream().anyMatch(t -> t.equalsIgnoreCase("lamp")));
      skus.add(product.sku());
    }
    List<String> sorted = new ArrayList<>(skus);
    sorted.sort(Comparator.naturalOrder());
    assertEquals(sorted, skus);
    String earliest = CatalogData.products().stream()
        .filter(p -> p.name().toLowerCase().contains("lamp") || p.tags().stream().anyMatch(t -> t.equalsIgnoreCase("lamp")))
        .map(Product::sku)
        .min(Comparator.naturalOrder())
        .orElseThrow();
    assertEquals(earliest, found.get(0).sku());
  }

  @Test
  void fuzzyFindsFlashSkuAndStaysInCpuBound() {
    SearchService search = new SearchService();
    search.setIntensity(1.0);
    assertEquals(4, search.fuzzyExpansions());
    for (int i = 0; i < 8; i++) {
      search.fuzzy("frostd lmp", 20);
    }
    long before = search.searchCpuMsP50();
    List<Product> found = search.fuzzy("frostd lmp", 20);
    assertTrue(found.stream().anyMatch(p -> p.sku().equals("SKU-00042")));
    long cpu = search.searchCpuMsP50();
    assertTrue(cpu >= 5 && cpu <= 80, "cpu ms " + cpu + " before " + before);
    search.setIntensity(0);
    assertEquals(1, search.fuzzyExpansions());
  }
}
