package com.jaframart.pricing.engine;

import com.jaframart.pricing.rules.PriceRule;

public interface Pipe {
  long run(PriceRule rule, long cents);
}
