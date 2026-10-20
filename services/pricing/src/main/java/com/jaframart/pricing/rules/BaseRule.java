package com.jaframart.pricing.rules;

public final class BaseRule implements PriceRule {
  public static final BaseRule INSTANCE = new BaseRule();

  public long apply(long cents, int quantity) {
    return cents;
  }

  public String name() {
    return "base";
  }
}
