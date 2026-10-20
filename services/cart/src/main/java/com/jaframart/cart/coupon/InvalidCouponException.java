package com.jaframart.cart.coupon;

public final class InvalidCouponException extends Exception {
  private final String rule;
  private final String code;

  public InvalidCouponException(String rule, String code) {
    super(rule + " failed for " + code);
    this.rule = rule;
    this.code = code;
  }

  public String rule() {
    return rule;
  }

  public String code() {
    return code;
  }
}
