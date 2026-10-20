package com.jaframart.loadgen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ScenarioCatalog {
  private final Map<String, Entry> byId = new LinkedHashMap<>();

  ScenarioCatalog() {
    try (InputStream in = ScenarioCatalog.class.getResourceAsStream("/scripts/scenarios.json")) {
      if (in == null) {
        throw new IllegalStateException("missing /scripts/scenarios.json");
      }
      Entry[] entries = Json.read(in, Entry[].class);
      for (Entry entry : entries) {
        byId.put(entry.id(), entry);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("failed to load scenarios.json", ex);
    }
  }

  Entry get(String id) {
    Entry entry = byId.get(id);
    if (entry == null) {
      throw new IllegalArgumentException("unknown scenario " + id);
    }
    return entry;
  }

  List<Entry> all() {
    return List.copyOf(byId.values());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Entry(String id, String name, String title, String service, List<Integer> subsystems,
               List<Effect> effects, Expected expected) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Effect(String service, List<Integer> subsystems) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Expected(List<String> primary, List<String> secondary) {}
}
