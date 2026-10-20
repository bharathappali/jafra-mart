package com.jaframart.pricing.api;

import com.jaframart.pricing.engine.CampaignEngine;
import com.jaframart.pricing.engine.QuoteService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api")
public class PricingResource {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final QuoteService quotes;
  private final CampaignEngine campaigns;

  @Inject
  public PricingResource(QuoteService quotes, CampaignEngine campaigns) {
    this.quotes = quotes;
    this.campaigns = campaigns;
  }

  @POST
  @Path("/quote")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response quote(QuoteRequest request) {
    if (request == null || request.items() == null || request.items().isEmpty()) {
      return ApiError.of(400, "BAD_REQUEST", "items required");
    }
    try {
      List<QuoteService.Item> items = new ArrayList<>();
      for (QuoteItem item : request.items()) {
        if (item.quantity() < 1) {
          return ApiError.of(400, "BAD_REQUEST", "quantity must be >= 1");
        }
        items.add(new QuoteService.Item(item.sku(), item.quantity()));
      }
      return Response.ok(quotes.quote(request.customerId(), request.couponCode(), items)).build();
    } catch (IllegalArgumentException ex) {
      return ApiError.of(404, "NOT_FOUND", ex.getMessage());
    }
  }

  @GET
  @Path("/campaigns")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> campaigns() {
    return Map.of(
        "active", campaigns.activeCampaignId(),
        "rulesLoaded", 3,
        "hiddenClassesLive", campaigns.hiddenClassesLive(),
        "campaignsLive", campaigns.campaignsLive());
  }

  @GET
  @Path("/info")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> info() {
    return Map.of(
        "component", "pricing",
        "framework", "quarkus 3.40.1",
        "gc", gc(),
        "java", System.getProperty("java.version"),
        "pod", com.jaframart.scenario.PodIdentity.pod(),
        "startedAt", ISO.format(STARTED));
  }

  static String gc() {
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = bean.getName();
      if (name.startsWith("G1")) {
        return "G1";
      }
      if (name.startsWith("PS")) {
        return "Parallel";
      }
      if (name.startsWith("ZGC")) {
        return "ZGC";
      }
      if (name.startsWith("Shenandoah")) {
        return "Shenandoah";
      }
      if ("Copy".equals(name) || "MarkSweepCompact".equals(name)) {
        return "Serial";
      }
    }
    return "unknown";
  }

  public record QuoteRequest(String customerId, String couponCode, List<QuoteItem> items) {}

  public record QuoteItem(String sku, int quantity) {}
}
