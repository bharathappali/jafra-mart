package com.jaframart.loadgen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class GroundTruthWriter {
  private final Path dir;
  private final Map<String, AtomicInteger> seqByRun = new ConcurrentHashMap<>();

  GroundTruthWriter(Path dir) {
    this.dir = dir;
    try {
      Files.createDirectories(dir);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  Path dir() {
    return dir;
  }

  private static final DateTimeFormatter MANUAL_DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
      .withZone(ZoneOffset.UTC);

  String manualRunId() {
    return "manual-" + MANUAL_DAY.format(java.time.Instant.now());
  }

  synchronized void write(String runId, Map<String, Object> fields) {
    AtomicInteger seq = seqByRun.computeIfAbsent(runId, ignored -> new AtomicInteger(0));
    int next = seq.incrementAndGet();
    LinkedHashMap<String, Object> line = new LinkedHashMap<>();
    line.put("schema", "jafra-mart/ground-truth/v1");
    line.put("runId", runId);
    line.put("seq", next);
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      if (!line.containsKey(entry.getKey())) {
        line.put(entry.getKey(), entry.getValue());
      }
    }
    Path file = dir.resolve(runId + ".jsonl");
    byte[] bytes = (Json.string(line) + "\n").getBytes(StandardCharsets.UTF_8);
    try (FileChannel channel = FileChannel.open(file,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(ByteBuffer.wrap(bytes));
      channel.force(false);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  byte[] readFile(String runId) throws IOException {
    Path file = dir.resolve(runId + ".jsonl");
    if (!Files.isRegularFile(file)) {
      return null;
    }
    return Files.readAllBytes(file);
  }
}
