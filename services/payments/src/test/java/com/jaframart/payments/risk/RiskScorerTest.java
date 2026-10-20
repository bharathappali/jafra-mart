package com.jaframart.payments.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScorerTest {
  @Test
  void riskScoreDeterministic() {
    int[] signals = RiskScorer.signals("JM-20261019-000123", "user", 23398, "USD", "tok_visa");
    assertEquals(RiskScorer.score(signals), RiskScorer.score(signals));
  }

  @Test
  void nativeQsortLinksWithoutBlocking() {
    int[] signals = RiskScorer.signals("JM-20261019-000456", "customer001", 1000, "USD", "tok_visa");
    int nativeScore = NativeRiskScorer.score(signals, false);
    assertEquals(RiskScorer.score(signals), nativeScore);
    assertTrue(nativeScore >= 0 && nativeScore < 100);
  }
}
