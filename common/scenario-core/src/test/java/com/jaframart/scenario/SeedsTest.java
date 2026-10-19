package com.jaframart.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SeedsTest {
  @Test
  void derivationIsStable() {
    long first = Seeds.derive(20261019L, "S01");
    long second = Seeds.derive(20261019L, "S01");
    assertEquals(first, second);
    assertNotEquals(first, Seeds.derive(20261019L, "S02"));
  }
}
