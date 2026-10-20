package com.jaframart.catalog.search;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class FuzzyMatcher {
  private FuzzyMatcher() {}

  private static final ThreadLocal<int[][]> MATRIX = ThreadLocal.withInitial(() -> new int[48][48]);

  public static int distance(String left, String right) {
    int n = left.length();
    int m = right.length();
    int[][] d = MATRIX.get();
    if (d.length <= n || d[0].length <= m) {
      d = new int[n + 8][m + 8];
      MATRIX.set(d);
    }
    for (int i = 0; i <= n; i++) {
      d[i][0] = i;
    }
    for (int j = 1; j <= m; j++) {
      d[0][j] = j;
    }
    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
        int best = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1);
        best = Math.min(best, d[i - 1][j - 1] + cost);
        if (i > 1 && j > 1
            && left.charAt(i - 1) == right.charAt(j - 2)
            && left.charAt(i - 2) == right.charAt(j - 1)) {
          best = Math.min(best, d[i - 2][j - 2] + 1);
        }
        d[i][j] = best;
      }
    }
    return d[n][m];
  }

  public static Set<String> trigrams(String text) {
    String normalized = text.toLowerCase(Locale.ROOT);
    Set<String> grams = new HashSet<>();
    if (normalized.length() < 3) {
      grams.add(normalized);
      return grams;
    }
    for (int i = 0; i <= normalized.length() - 3; i++) {
      grams.add(normalized.substring(i, i + 3));
    }
    return grams;
  }

  public static boolean sharesTrigram(String query, String name) {
    Set<String> grams = trigrams(name);
    for (String gram : trigrams(query)) {
      if (grams.contains(gram)) {
        return true;
      }
    }
    return false;
  }
}
