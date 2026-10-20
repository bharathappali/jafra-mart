package com.jaframart.catalog.thumb;

import java.nio.ByteBuffer;
import java.util.SplittableRandom;

public final class ThumbnailRenderer {
  private ThumbnailRenderer() {}

  public static void fill(ByteBuffer rgba, String sku, int size) {
    SplittableRandom random = new SplittableRandom(sku.hashCode());
    int c1 = random.nextInt();
    int c2 = random.nextInt();
    int span = Math.max(1, size * 2 - 2);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        double t = (x + y) / (double) span;
        rgba.put(mix(c1, c2, t, 16));
        rgba.put(mix(c1, c2, t, 8));
        rgba.put(mix(c1, c2, t, 0));
        rgba.put((byte) 255);
      }
    }
    rgba.flip();
  }

  private static byte mix(int c1, int c2, double t, int shift) {
    int a = (c1 >>> shift) & 0xFF;
    int b = (c2 >>> shift) & 0xFF;
    return (byte) Math.round(a + (b - a) * t);
  }
}
