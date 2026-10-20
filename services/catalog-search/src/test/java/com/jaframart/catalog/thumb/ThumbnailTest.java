package com.jaframart.catalog.thumb;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThumbnailTest {
  @Test
  void pngSignatureAndIhdr() {
    byte[] rgba = new byte[128 * 128 * 4];
    ThumbnailRenderer.fill(ByteBuffer.wrap(rgba), "SKU-00042", 128);
    byte[] png = PngEncoder.encode(rgba, 128);
    assertEquals((byte) 0x89, png[0]);
    assertEquals((byte) 0x50, png[1]);
    assertEquals((byte) 0x4E, png[2]);
    assertEquals((byte) 0x47, png[3]);
    assertEquals((byte) 0x0D, png[4]);
    assertEquals((byte) 0x0A, png[5]);
    assertEquals((byte) 0x1A, png[6]);
    assertEquals((byte) 0x0A, png[7]);
    int width = readInt(png, 16);
    int height = readInt(png, 20);
    assertEquals(128, width);
    assertEquals(128, height);
  }

  @Test
  void cacheEvictsAt64AndClosesArenas() throws Exception {
    Path dir = Files.createTempDirectory("thumbs");
    ThumbnailCache cache = new ThumbnailCache(dir);
    try {
      for (int i = 1; i <= 65; i++) {
        cache.png(String.format("SKU-%05d", i), 64);
      }
      assertEquals(64, cache.openArenas());
    } finally {
      cache.close();
    }
  }

  private static int readInt(byte[] png, int offset) {
    return ((png[offset] & 0xFF) << 24) | ((png[offset + 1] & 0xFF) << 16)
        | ((png[offset + 2] & 0xFF) << 8) | (png[offset + 3] & 0xFF);
  }
}
