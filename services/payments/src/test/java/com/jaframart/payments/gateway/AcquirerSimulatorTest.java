package com.jaframart.payments.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcquirerSimulatorTest {
  @Test
  void declineTokenPathIsSeparateFromLatency() {
    PaymentService service = new PaymentService();
    var declined = service.authorize("JM-20261019-000123", "user", 23398, "USD", "tok_decline");
    assertEquals("DECLINED", declined.status());
    assertEquals("P-000123", declined.paymentId());
  }

  @Test
  void riskScoreIsStableForFixedRequest() {
    PaymentService service = new PaymentService();
    var first = service.authorize("JM-20261019-000123", "user", 23398, "USD", "tok_visa");
    var second = service.authorize("JM-20261019-000123", "user", 23398, "USD", "tok_visa");
    assertEquals(first.riskScore(), second.riskScore());
    assertTrue(first.latencyMs() >= 30);
  }
}
