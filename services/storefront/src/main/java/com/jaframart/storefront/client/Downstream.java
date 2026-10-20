package com.jaframart.storefront.client;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class Downstream {
  private Downstream() {}

  public static ResponseEntity<byte[]> get(RestClient client, String uri) {
    return exchange(client, HttpMethod.GET, uri, null, false);
  }

  public static ResponseEntity<byte[]> getPassThrough(RestClient client, String uri) {
    return exchange(client, HttpMethod.GET, uri, null, true);
  }

  public static ResponseEntity<byte[]> post(RestClient client, String uri, Object body) {
    return exchange(client, HttpMethod.POST, uri, body, false);
  }

  public static ResponseEntity<byte[]> postPassThrough(RestClient client, String uri, Object body) {
    return exchange(client, HttpMethod.POST, uri, body, true);
  }

  public static ResponseEntity<byte[]> putPassThrough(RestClient client, String uri, Object body) {
    return exchange(client, HttpMethod.PUT, uri, body, true);
  }

  public static ResponseEntity<byte[]> delete(RestClient client, String uri) {
    return exchange(client, HttpMethod.DELETE, uri, null, false);
  }

  public static ResponseEntity<byte[]> exchange(RestClient client, HttpMethod method, String uri,
                                                Object body, boolean passThrough) {
    try {
      RestClient.RequestBodySpec spec = client.method(method).uri(uri);
      ResponseEntity<byte[]> entity;
      if (body instanceof byte[] bytes) {
        entity = spec.contentType(MediaType.APPLICATION_JSON).body(bytes).retrieve().toEntity(byte[].class);
      } else if (body instanceof String text) {
        entity = spec.contentType(MediaType.APPLICATION_JSON)
            .body(text.getBytes(StandardCharsets.UTF_8))
            .retrieve()
            .toEntity(byte[].class);
      } else if (body != null) {
        entity = spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(byte[].class);
      } else {
        entity = spec.retrieve().toEntity(byte[].class);
      }
      return sanitize(entity);
    } catch (ResourceAccessException ex) {
      return errorJson(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", ex.getMessage());
    } catch (RestClientResponseException ex) {
      if (passThrough) {
        MediaType contentType = ex.getResponseHeaders() != null
            ? ex.getResponseHeaders().getContentType()
            : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(ex.getStatusCode())
            .contentType(contentType != null ? contentType : MediaType.APPLICATION_JSON)
            .body(ex.getResponseBodyAsByteArray());
      }
      if (isTimeout(ex)) {
        return errorJson(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", ex.getMessage());
      }
      return errorJson(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", ex.getMessage());
    } catch (RuntimeException ex) {
      if (isTimeout(ex)) {
        return errorJson(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", ex.getMessage());
      }
      throw ex;
    }
  }

  /** Drop hop-by-hop headers; set Content-Length so the response is not chunked with a decoded body. */
  static ResponseEntity<byte[]> sanitize(ResponseEntity<byte[]> entity) {
    byte[] body = entity.getBody() != null ? entity.getBody() : new byte[0];
    MediaType contentType = entity.getHeaders().getContentType();
    if (contentType == null) {
      contentType = MediaType.APPLICATION_JSON;
    }
    return ResponseEntity.status(entity.getStatusCode())
        .contentType(contentType)
        .contentLength(body.length)
        .body(body);
  }

  public static <T> T getJson(RestClient client, String uri, Class<T> type) {
    try {
      return client.get().uri(uri).retrieve().body(type);
    } catch (ResourceAccessException ex) {
      throw new UpstreamException(504, "UPSTREAM_TIMEOUT", ex.getMessage(), ex);
    } catch (RestClientResponseException ex) {
      if (isTimeout(ex)) {
        throw new UpstreamException(504, "UPSTREAM_TIMEOUT", ex.getMessage(), ex);
      }
      throw new UpstreamException(502, "UPSTREAM_ERROR", ex.getMessage(), ex);
    }
  }

  public static ResponseEntity<byte[]> errorJson(HttpStatus status, String error, String message) {
    String msg = message == null ? error : message.replace("\\", "\\\\").replace("\"", "\\\"");
    String json = "{\"error\":\"" + error + "\",\"message\":\"" + msg + "\"}";
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean isTimeout(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof SocketTimeoutException || t instanceof ResourceAccessException) {
        return true;
      }
      String msg = t.getMessage();
      if (msg != null && (msg.contains("timed out") || msg.contains("Timeout"))) {
        return true;
      }
    }
    return false;
  }

  public static final class UpstreamException extends RuntimeException {
    private final int status;
    private final String error;

    public UpstreamException(int status, String error, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
      this.error = error;
    }

    public ResponseEntity<byte[]> toResponse() {
      return errorJson(HttpStatus.valueOf(status), error, getMessage());
    }
  }
}
