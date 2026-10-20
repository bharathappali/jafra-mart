package com.jaframart.orders.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

public final class ApiError {
  private ApiError() {}

  public static ResponseEntity<Map<String, String>> of(HttpStatus status, String error, String message) {
    return ResponseEntity.status(status).body(Map.of("error", error, "message", message));
  }
}
