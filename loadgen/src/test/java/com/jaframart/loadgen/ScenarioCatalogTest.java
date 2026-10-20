package com.jaframart.loadgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioCatalogTest {
  @Test
  void loadsThirteenScenarios() {
    ScenarioCatalog catalog = new ScenarioCatalog();
    assertEquals(13, catalog.all().size());
    assertEquals("cpu-flash-sale", catalog.get("S01").name());
    assertEquals("catalog-search", catalog.get("S01").service());
    assertFalse(catalog.get("S01").expected().primary().isEmpty());
    assertTrue(catalog.get("S07").subsystems().contains(16));
  }

  @Test
  void defaultScriptHasThirteenEntries() {
    ScriptDef script = ScriptDef.loadDefault();
    assertEquals("default-30m", script.script());
    assertEquals(1800, script.durationSeconds());
    assertEquals(13, script.entries().size());
    assertEquals(240, script.entries().get(0).startAt());
    assertEquals(1560, script.entries().get(12).startAt());
  }
}
