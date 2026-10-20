package com.jaframart.pricing.rules;

public final class RoundingRule implements PriceRule {
  public static final RoundingRule INSTANCE = new RoundingRule();

  public long apply(long cents, int quantity) {
    return cents;
  }

  public String name() {
    return "rounding";
  }
}
