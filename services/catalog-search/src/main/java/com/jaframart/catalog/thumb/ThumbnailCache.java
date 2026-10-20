package com.jaframart.catalog.thumb;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

@ApplicationScoped
public class ThumbnailCache {
  static final long MIB = 1024L * 1024L;
  private static final int BASELINE_ENTRIES = 64;

  private final Path directory;
  private final Map<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
  private final Deque<DiskFile> disk = new ArrayDeque<>();
  private boolean byteCap;
  private long capBytes = Long.MAX_VALUE;
  private long diskCapBytes;
  private long offHeapBytes;
  private long diskBytes;

  @jakarta.inject.Inject
  public ThumbnailCache(@ConfigProperty(name = "thumb.dir", defaultValue = "target/thumbs") String directory) {
    this(Path.of(directory));
  }

  public ThumbnailCache(Path directory) {
    this.directory = directory;
  }

  public synchronized byte[] png(String sku, int size) {
    String key = sku + ":" + size;
    Entry entry = entries.get(key);
    if (entry == null) {
      entry = render(sku, size);
      entries.put(key, entry);
      offHeapBytes += entry.bytes;
      evict();
      if (byteCap) {
        mirror(sku);
      }
    }
    ByteBuffer view = entry.segment.asByteBuffer();
    byte[] rgba = new byte[view.remaining()];
    view.get(rgba);
    return PngEncoder.encode(rgba, size);
  }

  public synchronized void applyIntensity(double intensity, boolean frozen) {
    if (intensity <= 0) {
      resetBaseline();
      return;
    }
    byteCap = true;
    long nextCap = Math.round(intensity * 104 * MIB);
    long nextDisk = Math.round(intensity * 128 * MIB);
    if (frozen) {
      capBytes = Math.min(capBytes == Long.MAX_VALUE ? nextCap : capBytes, nextCap);
      diskCapBytes = Math.min(diskCapBytes, nextDisk);
    } else {
      capBytes = nextCap;
      diskCapBytes = nextDisk;
    }
    evict();
    trimDisk();
  }

  public synchronized void resetBaseline() {
    byteCap = false;
    capBytes = Long.MAX_VALUE;
    diskCapBytes = 0;
    while (entries.size() > BASELINE_ENTRIES) {
      evictOne();
    }
    deleteAllDisk();
  }

  public synchronized int openArenas() {
    return entries.size();
  }

  public synchronized long offHeapBytes() {
    return offHeapBytes;
  }

  public synchronized long diskBytes() {
    return diskBytes;
  }

  public synchronized int cacheEntries() {
    return entries.size();
  }

  public synchronized void close() {
    List<Entry> copy = new ArrayList<>(entries.values());
    entries.clear();
    offHeapBytes = 0;
    for (Entry entry : copy) {
      entry.arena.close();
    }
    deleteAllDisk();
  }

  private Entry render(String sku, int size) {
    Arena arena = Arena.ofShared();
    MemorySegment segment = arena.allocate(size * (long) size * 4, ValueLayout.JAVA_BYTE.byteAlignment());
    ThumbnailRenderer.fill(segment.asByteBuffer(), sku, size);
    return new Entry(arena, segment, size * (long) size * 4);
  }

  private void evict() {
    while (overCap()) {
      evictOne();
    }
  }

  private boolean overCap() {
    if (entries.isEmpty()) {
      return false;
    }
    if (byteCap) {
      return offHeapBytes > capBytes;
    }
    return entries.size() > BASELINE_ENTRIES;
  }

  private void evictOne() {
    var eldest = entries.entrySet().iterator();
    if (!eldest.hasNext()) {
      return;
    }
    Entry entry = eldest.next().getValue();
    eldest.remove();
    offHeapBytes -= entry.bytes;
    entry.arena.close();
  }

  private void mirror(String sku) {
    int[] sizes = {64, 128, 256, 512};
    try {
      Files.createDirectories(directory);
      for (int size : sizes) {
        Path path = directory.resolve(sku + "-" + size + ".rgba");
        byte[] rgba = new byte[size * size * 4];
        ThumbnailRenderer.fill(ByteBuffer.wrap(rgba), sku, size);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
          channel.write(ByteBuffer.wrap(rgba));
        }
        disk.addLast(new DiskFile(path, rgba.length));
        diskBytes += rgba.length;
      }
    } catch (IOException ex) {
      throw new IllegalStateException("thumbnail mirror failed", ex);
    }
    trimDisk();
  }

  private void trimDisk() {
    while (diskBytes > diskCapBytes && !disk.isEmpty()) {
      DiskFile file = disk.removeFirst();
      try {
        Files.deleteIfExists(file.path);
      } catch (IOException ignored) {
        // a deleted file still drops the counter
      }
      diskBytes -= file.bytes;
    }
  }

  private void deleteAllDisk() {
    diskCapBytes = 0;
    trimDisk();
    diskBytes = 0;
    disk.clear();
  }

  private record Entry(Arena arena, MemorySegment segment, long bytes) {}

  private record DiskFile(Path path, long bytes) {}
}
