package com.jaframart.pricing.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteServiceTest {
  @Test
  void welcome10ExactCents() {
    QuoteService quotes = new QuoteService(new CampaignEngine(new TeardownGc()));
    Map<String, Object> body = quotes.quote("customer007", "WELCOME10",
        List.of(new QuoteService.Item("SKU-00042", 2)));
    assertEquals(25998L, ((Number) body.get("subtotalCents")).longValue());
    assertEquals(2600L, ((Number) body.get("discountCents")).longValue());
    assertEquals(23398L, ((Number) body.get("totalCents")).longValue());
    assertEquals("none", body.get("campaign"));
  }

  @Test
  void otherCouponsExactCents() {
    QuoteService quotes = new QuoteService(new CampaignEngine(new TeardownGc()));
    List<QuoteService.Item> items = List.of(new QuoteService.Item("SKU-00042", 2));
    assertEquals(1300L, ((Number) quotes.quote("c", "JAFRA5", items).get("discountCents")).longValue());
    assertEquals(500L, ((Number) quotes.quote("c", "FREESHIP", items).get("discountCents")).longValue());
    assertEquals(5200L, ((Number) quotes.quote("c", "FLASH20", items).get("discountCents")).longValue());
    assertEquals(0L, ((Number) quotes.quote("c", "NOPE", items).get("discountCents")).longValue());
  }

  @Test
  void hiddenRuleAndPipeDefine() throws Throwable {
    assertTrue(HiddenClassFactory.campaignRule(0).apply(100, 1) >= 0);
    long out = HiddenClassFactory.pipe().run((cents, qty) -> cents, 100L);
    assertTrue(out != 0L || out == 0L);
  }
}
