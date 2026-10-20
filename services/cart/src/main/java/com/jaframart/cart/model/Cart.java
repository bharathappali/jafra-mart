package com.jaframart.cart.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Cart {
  private final String customerId;
  private final Map<String, Integer> items = new LinkedHashMap<>();
  private String couponCode;

  public Cart(String customerId) {
    this.customerId = customerId;
  }

  public String customerId() {
    return customerId;
  }

  public Map<String, Integer> items() {
    return items;
  }

  public String couponCode() {
    return couponCode;
  }

  public void setCouponCode(String couponCode) {
    this.couponCode = couponCode;
  }

  public boolean hasSku(String sku) {
    return items.containsKey(sku);
  }

  public List<Map<String, Object>> itemViews() {
    List<Map<String, Object>> views = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : items.entrySet()) {
      views.add(Map.of("sku", entry.getKey(), "quantity", entry.getValue()));
    }
    return views;
  }
}
