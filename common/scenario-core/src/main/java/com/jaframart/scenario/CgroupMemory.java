package com.jaframart.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CgroupMemory {
  public static final Path CURRENT = Path.of("/sys/fs/cgroup/memory.current");
  public static final Path STAT = Path.of("/sys/fs/cgroup/memory.stat");
  public static final Path MAX = Path.of("/sys/fs/cgroup/memory.max");

  private CgroupMemory() {}

  public record Reading(long current, long inactiveFile, long max) {
    public long workingSet() {
      return current - inactiveFile;
    }
  }

  public static Reading read() {
    return read(CURRENT, STAT, MAX);
  }

  static Reading read(Path current, Path stat, Path max) {
    if (!Files.isRegularFile(current) || !Files.isRegularFile(stat) || !Files.isRegularFile(max)) {
      return null;
    }
    try {
      long currentBytes = Long.parseLong(Files.readString(current).trim());
      long inactive = 0L;
      boolean sawInactive = false;
      for (String line : Files.readString(stat).split("\n")) {
        if (line.startsWith("inactive_file ")) {
          inactive = Long.parseLong(line.substring("inactive_file ".length()).trim());
          sawInactive = true;
          break;
        }
      }
      if (!sawInactive) {
        return null;
      }
      String maxText = Files.readString(max).trim();
      if ("max".equals(maxText)) {
        return null;
      }
      return new Reading(currentBytes, inactive, Long.parseLong(maxText));
    } catch (IOException | NumberFormatException ex) {
      return null;
    }
  }
}
