package com.jaframart.cart.coupon;

import com.jaframart.cart.model.Cart;
import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Coupon;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class CouponValidator {
  private static final Set<String> BLACKLIST = Set.of("BLACKLIST", "HACKED99");
  private final List<CouponRule> rules = buildRules();
  private final AtomicLong exceptionsThrown = new AtomicLong();
  private final AtomicLong couponChecks = new AtomicLong();
  private volatile boolean storm;

  public void setStorm(boolean storm) {
    this.storm = storm;
  }

  public Map<String, Object> validate(String code, Cart cart) {
    couponChecks.incrementAndGet();
    String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    List<String> failures = new ArrayList<>();
    for (CouponRule rule : rules) {
      try {
        rule.check(normalized, cart);
      } catch (InvalidCouponException ex) {
        countThrow(ex);
        failures.add(ex.rule());
        if (!storm) {
          return Map.of("valid", false, "code", normalized, "message", messageFor(normalized, failures));
        }
      } catch (NumberFormatException ex) {
        countThrow(ex);
        InvalidCouponException wrapped = new InvalidCouponException("checksum", normalized);
        countThrow(wrapped);
        failures.add(wrapped.rule());
        if (!storm) {
          return Map.of("valid", false, "code", normalized, "message", messageFor(normalized, failures));
        }
      }
    }
    if (!failures.isEmpty()) {
      return Map.of("valid", false, "code", normalized, "message", messageFor(normalized, failures));
    }
    Coupon coupon = CatalogData.coupon(normalized);
    return Map.of("valid", true, "code", normalized, "message", coupon == null ? "ok" : coupon.message());
  }

  public long exceptionsThrown() {
    return exceptionsThrown.get();
  }

  public long couponChecks() {
    return couponChecks.get();
  }

  public void resetCounters() {
    exceptionsThrown.set(0);
    couponChecks.set(0);
  }

  private void countThrow(Throwable throwable) {
    exceptionsThrown.incrementAndGet();
    if (storm) {
      for (int i = 0; i < 3; i++) {
        try {
          throw new InvalidCouponException("storm", throwable.getMessage() == null ? "" : throwable.getMessage());
        } catch (InvalidCouponException again) {
          exceptionsThrown.incrementAndGet();
        }
      }
    }
  }

  private static String messageFor(String code, List<String> failures) {
    if (CatalogData.coupon(code) == null) {
      return "Unknown coupon";
    }
    return "Invalid coupon (" + String.join(",", failures) + ")";
  }

  private List<CouponRule> buildRules() {
    List<CouponRule> list = new ArrayList<>();
    list.add(rule("format", (code, cart) -> {
      if (code.isEmpty() || !Character.isLetter(code.charAt(0))) {
        throw new InvalidCouponException("format", code);
      }
    }));
    list.add(rule("length", (code, cart) -> {
      if (code.length() < 4 || code.length() > 16) {
        throw new InvalidCouponException("length", code);
      }
    }));
    list.add(rule("charset", (code, cart) -> {
      for (int i = 0; i < code.length(); i++) {
        char c = code.charAt(i);
        if (!(Character.isLetterOrDigit(c))) {
          throw new InvalidCouponException("charset", code);
        }
      }
    }));
    list.add(rule("checksum", (code, cart) -> {
      String digits = code.replaceAll("\\D+", "");
      if (!digits.isEmpty()) {
        Integer.parseInt(digits);
      } else if (CatalogData.coupon(code) == null) {
        Integer.parseInt(code);
      }
    }));
    list.add(rule("expiry", (code, cart) -> {
      if (code.startsWith("OLD")) {
        throw new InvalidCouponException("expiry", code);
      }
    }));
    list.add(rule("region", (code, cart) -> {
      if (code.startsWith("EU")) {
        throw new InvalidCouponException("region", code);
      }
    }));
    list.add(rule("first-order", (code, cart) -> {
      if ("FIRSTONLY".equals(code)) {
        throw new InvalidCouponException("first-order", code);
      }
    }));
    list.add(rule("flash-only", (code, cart) -> {
      if ("FLASH20".equals(code) && !cart.hasSku(CatalogData.FLASH_SKU)) {
        throw new InvalidCouponException("flash-only", code);
      }
    }));
    list.add(rule("stacking", (code, cart) -> {
      if (cart.couponCode() != null && !cart.couponCode().equals(code) && code.startsWith("STACK")) {
        throw new InvalidCouponException("stacking", code);
      }
    }));
    list.add(rule("per-customer", (code, cart) -> {
      if ("ONCEONLY".equals(code) && "customer200".equals(cart.customerId())) {
        throw new InvalidCouponException("per-customer", code);
      }
    }));
    list.add(rule("blacklist", (code, cart) -> {
      if (BLACKLIST.contains(code)) {
        throw new InvalidCouponException("blacklist", code);
      }
    }));
    list.add(rule("existence", (code, cart) -> {
      if (CatalogData.coupon(code) == null) {
        throw new InvalidCouponException("existence", code);
      }
    }));
    return List.copyOf(list);
  }

  private static CouponRule rule(String name, Checker checker) {
    return new CouponRule() {
      public String name() {
        return name;
      }

      public void check(String code, Cart cart) throws InvalidCouponException {
        checker.check(code, cart);
      }
    };
  }

  @FunctionalInterface
  private interface Checker {
    void check(String code, Cart cart) throws InvalidCouponException;
  }
}
