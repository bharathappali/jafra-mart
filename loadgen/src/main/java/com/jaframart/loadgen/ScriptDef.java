package com.jaframart.loadgen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.InputStream;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record ScriptDef(String script, int durationSeconds, List<Entry> entries) {
  static ScriptDef loadDefault() {
    try (InputStream in = ScriptDef.class.getResourceAsStream("/scripts/default-30m.json")) {
      if (in == null) {
        throw new IllegalStateException("missing /scripts/default-30m.json");
      }
      return Json.read(in, ScriptDef.class);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to load default-30m.json", ex);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Entry(String id, int startAt, int ramp, int hold, int recover, double intensity) {}
}
