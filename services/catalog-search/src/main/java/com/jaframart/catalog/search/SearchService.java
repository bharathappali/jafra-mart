package com.jaframart.catalog.search;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Product;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class SearchService {
  private static final String[][] NAME_TOKENS = nameTokens();
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
  private final List<Long> cpuSamples = new ArrayList<>();
  private final AtomicLong fuzzySearches = new AtomicLong();
  private volatile double intensity;

  public SearchService() {
    if (threads.isThreadCpuTimeSupported()) {
      threads.setThreadCpuTimeEnabled(true);
    }
  }

  public void setIntensity(double intensity) {
    this.intensity = Math.max(0, intensity);
  }

  public double intensity() {
    return intensity;
  }

  public int fuzzyExpansions() {
    if (intensity <= 0) {
      return 1;
    }
    return 1 + (int) Math.round(3 * intensity);
  }

  public boolean prefilter() {
    return intensity <= 0;
  }

  public List<Product> exact(String query, int limit) {
    String[] tokens = tokens(query);
    List<Scored> scored = new ArrayList<>();
    for (Product product : CatalogData.products()) {
      int matched = matchedTokens(tokens, product);
      if (matched > 0) {
        scored.add(new Scored(product, matched));
      }
    }
    scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> s.product.sku()));
    return take(scored, limit);
  }

  public List<Product> fuzzy(String query, int limit) {
    long before = threads.getCurrentThreadCpuTime();
    List<String> variants = variants(query, fuzzyExpansions());
    boolean filter = prefilter();
    List<Product> products = CatalogData.products();
    int[] topScore = new int[limit];
    int[] topIndex = new int[limit];
    int filled = 0;
    for (int index = 0; index < products.size(); index++) {
      if (filter && !FuzzyMatcher.sharesTrigram(query, products.get(index).name())) {
        continue;
      }
      String[] nameTokens = NAME_TOKENS[index];
      int best = Integer.MAX_VALUE;
      outer:
      for (String variant : variants) {
        for (String nameToken : nameTokens) {
          best = Math.min(best, FuzzyMatcher.distance(variant, nameToken));
          if (best == 0) {
            break outer;
          }
        }
      }
      if (nameTokens.length == 0) {
        best = variants.isEmpty() ? 0 : variants.get(0).length();
      }
      filled = consider(products, topScore, topIndex, filled, index, best);
    }
    sortTop(products, topScore, topIndex, filled);
    List<Product> result = new ArrayList<>(filled);
    for (int i = 0; i < filled; i++) {
      result.add(products.get(topIndex[i]));
    }
    long after = threads.getCurrentThreadCpuTime();
    if (before >= 0 && after >= before) {
      recordCpu((after - before) / 1_000_000L);
    }
    fuzzySearches.incrementAndGet();
    return result;
  }

  public long fuzzySearches() {
    return fuzzySearches.get();
  }

  public long searchCpuMsP50() {
    synchronized (cpuSamples) {
      if (cpuSamples.isEmpty()) {
        return 0;
      }
      List<Long> copy = new ArrayList<>(cpuSamples);
      copy.sort(Long::compareTo);
      return copy.get(copy.size() / 2);
    }
  }

  private void recordCpu(long millis) {
    synchronized (cpuSamples) {
      cpuSamples.add(millis);
      if (cpuSamples.size() > 64) {
        cpuSamples.remove(0);
      }
    }
  }

  static List<String> variants(String query, int expansions) {
    String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
    List<String> variants = new ArrayList<>();
    variants.add(normalized);
    for (int i = 1; i < expansions && i <= normalized.length(); i++) {
      variants.add(normalized.substring(0, i - 1) + normalized.substring(i));
    }
    return variants;
  }

  private static String[] tokens(String text) {
    if (text == null || text.isBlank()) {
      return new String[0];
    }
    return Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+")).filter(s -> !s.isEmpty()).toArray(String[]::new);
  }

  private static int matchedTokens(String[] queryTokens, Product product) {
    String[] nameTokens = tokens(product.name());
    int matched = 0;
    for (String token : queryTokens) {
      boolean hit = false;
      for (String nameToken : nameTokens) {
        if (nameToken.equals(token)) {
          hit = true;
          break;
        }
      }
      if (!hit) {
        for (String tag : product.tags()) {
          if (tag.equalsIgnoreCase(token)) {
            hit = true;
            break;
          }
        }
      }
      if (hit) {
        matched++;
      }
    }
    return matched;
  }

  private static int consider(List<Product> products, int[] topScore, int[] topIndex, int filled, int index, int score) {
    if (filled < topScore.length) {
      topScore[filled] = score;
      topIndex[filled] = index;
      return filled + 1;
    }
    int worst = 0;
    for (int i = 1; i < topScore.length; i++) {
      if (worse(products, topScore, topIndex, i, worst)) {
        worst = i;
      }
    }
    if (worse(products, topScore, topIndex, worst, index, score)) {
      topScore[worst] = score;
      topIndex[worst] = index;
    }
    return filled;
  }

  private static boolean worse(List<Product> products, int[] topScore, int[] topIndex, int left, int right) {
    return worse(products, topScore, topIndex, left, topIndex[right], topScore[right]);
  }

  private static boolean worse(List<Product> products, int[] topScore, int[] topIndex, int left, int rightIndex, int rightScore) {
    if (topScore[left] != rightScore) {
      return topScore[left] > rightScore;
    }
    return products.get(topIndex[left]).sku().compareTo(products.get(rightIndex).sku()) > 0;
  }

  private static void sortTop(List<Product> products, int[] topScore, int[] topIndex, int filled) {
    for (int i = 1; i < filled; i++) {
      int score = topScore[i];
      int index = topIndex[i];
      int j = i - 1;
      while (j >= 0 && worse(products, topScore, topIndex, j, index, score)) {
        topScore[j + 1] = topScore[j];
        topIndex[j + 1] = topIndex[j];
        j--;
      }
      topScore[j + 1] = score;
      topIndex[j + 1] = index;
    }
  }

  private static String[][] nameTokens() {
    List<Product> products = CatalogData.products();
    String[][] tokens = new String[products.size()][];
    for (int i = 0; i < products.size(); i++) {
      tokens[i] = tokens(products.get(i).name());
    }
    return tokens;
  }

  private static List<Product> take(List<Scored> scored, int limit) {
    List<Product> products = new ArrayList<>(Math.min(limit, scored.size()));
    for (int i = 0; i < scored.size() && products.size() < limit; i++) {
      products.add(scored.get(i).product);
    }
    return products;
  }

  private record Scored(Product product, int score) {}
}
