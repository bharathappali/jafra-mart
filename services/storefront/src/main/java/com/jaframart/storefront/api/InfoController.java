package com.jaframart.storefront.api;

import com.jaframart.scenario.PodIdentity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InfoController {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  @GetMapping("/info")
  public Map<String, String> info() {
    return Map.of(
        "component", "storefront",
        "framework", "spring-boot 4.1.1",
        "gc", gc(),
        "java", System.getProperty("java.version"),
        "pod", PodIdentity.pod(),
        "startedAt", ISO.format(STARTED));
  }

  static String gc() {
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = bean.getName();
      if (name.startsWith("G1")) {
        return "G1";
      }
      if (name.startsWith("PS")) {
        return "Parallel";
      }
      if (name.startsWith("ZGC")) {
        return "ZGC";
      }
      if (name.startsWith("Shenandoah")) {
        return "Shenandoah";
      }
      if ("Copy".equals(name) || "MarkSweepCompact".equals(name)) {
        return "Serial";
      }
    }
    return "unknown";
  }
}
