package com.jaframart.cart.coupon;

import com.jaframart.cart.model.Cart;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouponValidatorTest {
  @Test
  void validCouponsAndFlashRule() {
    CouponValidator validator = new CouponValidator();
    Cart cart = new Cart("customer001");
    cart.items().put("SKU-00001", 1);
    assertTrue((Boolean) validator.validate("WELCOME10", cart).get("valid"));
    assertTrue((Boolean) validator.validate("JAFRA5", cart).get("valid"));
    assertTrue((Boolean) validator.validate("FREESHIP", cart).get("valid"));
    assertFalse((Boolean) validator.validate("FLASH20", cart).get("valid"));
    cart.items().put("SKU-00042", 1);
    assertTrue((Boolean) validator.validate("FLASH20", cart).get("valid"));
  }

  @Test
  void badCodeInvalid() {
    CouponValidator validator = new CouponValidator();
    Map<String, Object> result = validator.validate("BADCODE1", new Cart("user"));
    assertFalse((Boolean) result.get("valid"));
    assertEquals("Unknown coupon", result.get("message"));
  }
}
