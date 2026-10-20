package com.jaframart.pricing.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class VolumeRule implements PriceRule {
  public static final VolumeRule INSTANCE = new VolumeRule();

  public long apply(long cents, int quantity) {
    if (quantity < 3) {
      return cents;
    }
    return BigDecimal.valueOf(cents)
        .multiply(BigDecimal.valueOf(97))
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public String name() {
    return "volume";
  }
}
