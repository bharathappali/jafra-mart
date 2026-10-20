package com.jaframart.storefront.api;

import com.jaframart.storefront.client.Downstream;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@RestController
public class ConductorProxyController {
  private final RestClient loadgen;

  public ConductorProxyController(@Qualifier("loadgenRestClient") RestClient loadgen) {
    this.loadgen = loadgen;
  }

  @RequestMapping(
      path = "/api/admin/conductor/**",
      method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
  public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
    String uri = request.getRequestURI();
    String suffix = uri.substring("/api/admin/conductor".length());
    String target = "/api" + suffix;
    String query = request.getQueryString();
    if (query != null && !query.isBlank()) {
      target = target + "?" + query;
    }
    HttpMethod method = HttpMethod.valueOf(request.getMethod());
    byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
    if (method == HttpMethod.GET) {
      return Downstream.getPassThrough(loadgen, target);
    }
    byte[] payload = body.length == 0 ? "{}".getBytes() : body;
    if (method == HttpMethod.PUT) {
      return Downstream.putPassThrough(loadgen, target, payload);
    }
    return Downstream.postPassThrough(loadgen, target, payload);
  }
}
