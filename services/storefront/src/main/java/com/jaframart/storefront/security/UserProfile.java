package com.jaframart.storefront.security;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Customer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UserProfile {
  private UserProfile() {}

  public static Map<String, Object> from(Authentication auth) {
    String username = auth.getName();
    List<String> roles = auth.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(a -> "ROLE_USER".equals(a) || "ROLE_ADMIN".equals(a))
        .map(a -> a.substring(5))
        .sorted()
        .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("displayName", displayName(username));
    body.put("roles", roles);
    return body;
  }

  public static String displayName(String username) {
    if ("admin".equals(username)) {
      return "Admin";
    }
    for (Customer customer : CatalogData.customers()) {
      if (customer.username().equals(username)) {
        return customer.displayName();
      }
    }
    return username;
  }
}
