package com.jaframart.catalog.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public final class ApiError {
  private ApiError() {}

  public static Response of(int status, String error, String message) {
    return Response.status(status).type(MediaType.APPLICATION_JSON)
        .entity(new Body(error, message)).build();
  }

  public record Body(String error, String message) {}
}
