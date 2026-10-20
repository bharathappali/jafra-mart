package com.jaframart.cart.model;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Product;
import com.jaframart.cart.coupon.CouponValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CartService {
  private final ConcurrentHashMap<String, Cart> carts = new ConcurrentHashMap<>();
  private final CouponValidator validator;

  @Inject
  public CartService(CouponValidator validator) {
    this.validator = validator;
  }

  public Map<String, Object> get(String customerId) {
    return view(cart(customerId));
  }

  public Map<String, Object> addItem(String customerId, String sku, int quantity) {
    if (quantity < 1 || quantity > 10) {
      throw new IllegalArgumentException("quantity must be 1..10");
    }
    Product product = CatalogData.product(sku);
    if (product == null) {
      throw new IllegalStateException("unknown sku " + sku);
    }
    Cart cart = cart(customerId);
    cart.items().merge(sku, quantity, Integer::sum);
    if (cart.items().get(sku) > 10) {
      cart.items().put(sku, 10);
    }
    return view(cart);
  }

  public Map<String, Object> removeItem(String customerId, String sku) {
    Cart cart = cart(customerId);
    cart.items().remove(sku);
    return view(cart);
  }

  public Map<String, Object> applyCoupon(String customerId, String code) {
    Cart cart = cart(customerId);
    Map<String, Object> result = validator.validate(code, cart);
    if (Boolean.TRUE.equals(result.get("valid"))) {
      cart.setCouponCode((String) result.get("code"));
    }
    return result;
  }

  public void clear(String customerId) {
    carts.remove(customerId);
  }

  private Cart cart(String customerId) {
    return carts.computeIfAbsent(customerId, Cart::new);
  }

  private Map<String, Object> view(Cart cart) {
    long subtotal = 0;
    for (Map.Entry<String, Integer> entry : cart.items().entrySet()) {
      Product product = CatalogData.product(entry.getKey());
      if (product != null) {
        subtotal += product.priceCents() * (long) entry.getValue();
      }
    }
    java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("customerId", cart.customerId());
    body.put("items", cart.itemViews());
    body.put("couponCode", cart.couponCode());
    body.put("subtotalCents", subtotal);
    return body;
  }
}
