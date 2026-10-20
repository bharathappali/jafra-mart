package com.jaframart.cart.coupon;

import com.jaframart.cart.model.Cart;

public interface CouponRule {
  String name();

  void check(String code, Cart cart) throws InvalidCouponException;
}
