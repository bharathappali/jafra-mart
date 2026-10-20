package com.jaframart.payments.risk;

import java.util.Arrays;

public final class RiskScorer {
  private RiskScorer() {}

  public static int[] signals(String orderNumber, String customerId, long amountCents, String currency, String cardToken) {
    int[] values = new int[6];
    values[0] = orderNumber == null ? 0 : orderNumber.hashCode();
    values[1] = customerId == null ? 0 : customerId.hashCode();
    values[2] = (int) amountCents;
    values[3] = currency == null ? 0 : currency.hashCode();
    values[4] = cardToken == null ? 0 : cardToken.hashCode();
    values[5] = (orderNumber == null ? 0 : orderNumber.length()) * 31
        + (customerId == null ? 0 : customerId.length());
    return values;
  }

  public static int score(int[] signals) {
    int[] copy = Arrays.copyOf(signals, signals.length);
    Arrays.sort(copy);
    long sum = 0;
    for (int value : copy) {
      sum += value;
    }
    return (int) (Math.floorMod(sum, 100L));
  }
}
