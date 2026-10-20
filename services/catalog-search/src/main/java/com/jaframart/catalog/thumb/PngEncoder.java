package com.jaframart.catalog.thumb;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public final class PngEncoder {
  private PngEncoder() {}

  public static byte[] encode(byte[] rgba, int size) {
    byte[] raw = new byte[size * (1 + size * 4)];
    for (int y = 0; y < size; y++) {
      int row = y * (1 + size * 4);
      raw[row] = 0;
      System.arraycopy(rgba, y * size * 4, raw, row + 1, size * 4);
    }
    byte[] zlib = deflate(raw);
    ByteArrayOutputStream out = new ByteArrayOutputStream(zlib.length + 128);
    out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    byte[] ihdr = new byte[13];
    writeInt(ihdr, 0, size);
    writeInt(ihdr, 4, size);
    ihdr[8] = 8;
    ihdr[9] = 6;
    chunk(out, "IHDR", ihdr);
    chunk(out, "IDAT", zlib);
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  private static byte[] deflate(byte[] raw) {
    Deflater deflater = new Deflater(6);
    deflater.setInput(raw);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
    byte[] buf = new byte[8192];
    while (!deflater.finished()) {
      int n = deflater.deflate(buf);
      out.write(buf, 0, n);
    }
    deflater.end();
    return out.toByteArray();
  }

  private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
    writeInt(out, data.length);
    byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
    out.writeBytes(typeBytes);
    out.writeBytes(data);
    CRC32 crc = new CRC32();
    crc.update(typeBytes);
    crc.update(data);
    writeInt(out, (int) crc.getValue());
  }

  private static void writeInt(ByteArrayOutputStream out, int value) {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  private static void writeInt(byte[] dest, int offset, int value) {
    dest[offset] = (byte) ((value >>> 24) & 0xFF);
    dest[offset + 1] = (byte) ((value >>> 16) & 0xFF);
    dest[offset + 2] = (byte) ((value >>> 8) & 0xFF);
    dest[offset + 3] = (byte) (value & 0xFF);
  }
}
