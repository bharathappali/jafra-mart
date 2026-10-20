package com.jaframart.pricing.rules;

public interface PriceRule {
  long apply(long cents, int quantity);
}
