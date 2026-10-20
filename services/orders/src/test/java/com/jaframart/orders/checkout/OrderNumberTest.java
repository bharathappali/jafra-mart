package com.jaframart.orders.checkout;

import com.jaframart.orders.store.InMemoryOrderStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderNumberTest {
  private static final Pattern FORMAT = Pattern.compile("^JM-\\d{8}-\\d{6}$");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  @Test
  void allocateMatchesSpecFormat() {
    InMemoryOrderStore store = new InMemoryOrderStore();
    String first = store.allocateOrderNumber();
    String second = store.allocateOrderNumber();
    assertTrue(FORMAT.matcher(first).matches(), first);
    assertTrue(FORMAT.matcher(second).matches(), second);
    String today = DAY.format(Instant.now());
    assertTrue(first.startsWith("JM-" + today + "-"));
    assertEquals("JM-" + today + "-000001", first);
    assertEquals("JM-" + today + "-000002", second);
  }
}
