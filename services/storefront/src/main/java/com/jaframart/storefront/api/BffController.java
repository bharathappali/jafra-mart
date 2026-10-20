package com.jaframart.storefront.api;

import com.jaframart.storefront.client.Downstream;
import com.jaframart.storefront.security.UserProfile;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BffController {
  private final RestClient catalog;
  private final RestClient catalogSearch;
  private final RestClient inventory;
  private final RestClient cart;
  private final RestClient orders;
  private final RestClient recommendations;

  public BffController(
      @Qualifier("catalogRestClient") RestClient catalog,
      @Qualifier("catalogSearchRestClient") RestClient catalogSearch,
      @Qualifier("inventoryRestClient") RestClient inventory,
      @Qualifier("cartRestClient") RestClient cart,
      @Qualifier("ordersRestClient") RestClient orders,
      @Qualifier("recommendationsRestClient") RestClient recommendations) {
    this.catalog = catalog;
    this.catalogSearch = catalogSearch;
    this.inventory = inventory;
    this.cart = cart;
    this.orders = orders;
    this.recommendations = recommendations;
  }

  @GetMapping("/catalog/products")
  public ResponseEntity<byte[]> products(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String category) {
    UriComponentsBuilder b = UriComponentsBuilder.fromPath("/api/products");
    if (page != null) {
      b.queryParam("page", page);
    }
    if (size != null) {
      b.queryParam("size", size);
    }
    if (category != null) {
      b.queryParam("category", category);
    }
    return Downstream.get(catalog, encode(b));
  }

  @GetMapping("/catalog/search")
  public ResponseEntity<byte[]> search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean fuzzy,
      @RequestParam(required = false) Integer limit) {
    UriComponentsBuilder b = UriComponentsBuilder.fromPath("/api/search");
    if (q != null) {
      b.queryParam("q", q);
    }
    if (fuzzy != null) {
      b.queryParam("fuzzy", fuzzy);
    }
    if (limit != null) {
      b.queryParam("limit", limit);
    }
    return Downstream.get(catalogSearch, encode(b));
  }

  @GetMapping("/catalog/products/{sku}")
  public ResponseEntity<?> productDetail(@PathVariable String sku) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> product = Downstream.getJson(catalog, "/api/products/" + sku, Map.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> availability = Downstream.getJson(inventory, "/api/inventory/" + sku, Map.class);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("product", product);
      body.put("availability", availability);
      return ResponseEntity.ok(body);
    } catch (Downstream.UpstreamException ex) {
      return ex.toResponse();
    }
  }

  @GetMapping("/catalog/products/{sku}/thumbnail")
  public ResponseEntity<byte[]> thumbnail(
      @PathVariable String sku,
      @RequestParam(required = false) Integer size) {
    UriComponentsBuilder b = UriComponentsBuilder.fromPath("/api/products/" + sku + "/thumbnail");
    if (size != null) {
      b.queryParam("size", size);
    }
    ResponseEntity<byte[]> entity = Downstream.get(catalog, encode(b));
    if (entity.getStatusCode().is2xxSuccessful()) {
      return ResponseEntity.status(entity.getStatusCode())
          .contentType(MediaType.IMAGE_PNG)
          .body(entity.getBody());
    }
    return entity;
  }

  @GetMapping("/catalog/flash")
  public ResponseEntity<byte[]> flash() {
    return Downstream.get(catalog, "/api/flash");
  }

  @GetMapping("/recommendations")
  public ResponseEntity<byte[]> recommendations(
      @RequestParam String productId,
      Authentication authentication,
      HttpSession session) {
    String customerId = authentication.getName();
    String sessionId = session.getId();
    String uri = encode(UriComponentsBuilder.fromPath("/api/recommendations")
        .queryParam("customerId", customerId)
        .queryParam("productId", productId)
        .queryParam("sessionId", sessionId));
    return Downstream.get(recommendations, uri);
  }

  @GetMapping("/cart")
  public ResponseEntity<byte[]> getCart(Authentication authentication) {
    return Downstream.get(cart, "/api/carts/" + authentication.getName());
  }

  @PostMapping("/cart/items")
  public ResponseEntity<byte[]> addItem(Authentication authentication, @RequestBody Map<String, Object> body) {
    return Downstream.post(cart, "/api/carts/" + authentication.getName() + "/items", body);
  }

  @DeleteMapping("/cart/items/{sku}")
  public ResponseEntity<byte[]> removeItem(Authentication authentication, @PathVariable String sku) {
    return Downstream.delete(cart, "/api/carts/" + authentication.getName() + "/items/" + sku);
  }

  @PostMapping("/cart/coupon")
  public ResponseEntity<byte[]> coupon(Authentication authentication, @RequestBody Map<String, Object> body) {
    return Downstream.post(cart, "/api/carts/" + authentication.getName() + "/coupon", body);
  }

  @DeleteMapping("/cart")
  public ResponseEntity<byte[]> clearCart(Authentication authentication) {
    return Downstream.delete(cart, "/api/carts/" + authentication.getName());
  }

  @PostMapping("/checkout")
  public ResponseEntity<byte[]> checkout(Authentication authentication, @RequestBody Map<String, Object> body) {
    String customerId = authentication.getName();
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> cartBody = Downstream.getJson(cart, "/api/carts/" + customerId, Map.class);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> items = (List<Map<String, Object>>) cartBody.get("items");
      if (items == null) {
        items = List.of();
      }
      List<Map<String, Object>> orderItems = new ArrayList<>();
      for (Map<String, Object> item : items) {
        orderItems.add(Map.of(
            "sku", String.valueOf(item.get("sku")),
            "quantity", ((Number) item.get("quantity")).intValue()));
      }
      Object coupon = cartBody.get("couponCode");
      Map<String, Object> orderReq = new LinkedHashMap<>();
      orderReq.put("customerId", customerId);
      orderReq.put("customerName", UserProfile.displayName(customerId));
      orderReq.put("couponCode", coupon);
      orderReq.put("cardToken", body == null ? null : body.get("cardToken"));
      orderReq.put("items", orderItems);

      ResponseEntity<byte[]> placed = Downstream.postPassThrough(orders, "/api/orders", orderReq);
      if (placed.getStatusCode().value() == 201) {
        Downstream.delete(cart, "/api/carts/" + customerId);
      }
      return placed;
    } catch (Downstream.UpstreamException ex) {
      return ex.toResponse();
    }
  }

  @GetMapping("/orders/mine")
  public ResponseEntity<byte[]> myOrders(Authentication authentication) {
    String uri = encode(UriComponentsBuilder.fromPath("/api/orders")
        .queryParam("customerId", authentication.getName())
        .queryParam("limit", 20));
    return Downstream.get(orders, uri);
  }

  @GetMapping("/inventory/{sku}")
  public ResponseEntity<byte[]> inventory(@PathVariable String sku) {
    return Downstream.get(inventory, "/api/inventory/" + sku);
  }

  /** Encode query values; {@code build(true)} rejects spaces in decoded params (§10.9). */
  private static String encode(UriComponentsBuilder b) {
    return b.encode().build().toUriString();
  }
}
