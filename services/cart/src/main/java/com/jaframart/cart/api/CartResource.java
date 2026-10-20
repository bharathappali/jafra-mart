package com.jaframart.cart.api;

import com.jaframart.cart.model.CartService;
import com.jaframart.scenario.PodIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@ApplicationScoped
@Path("/api")
public class CartResource {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final CartService carts;

  @Inject
  public CartResource(CartService carts) {
    this.carts = carts;
  }

  @GET
  @Path("/carts/{customerId}")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> get(@PathParam("customerId") String customerId) {
    return carts.get(customerId);
  }

  @POST
  @Path("/carts/{customerId}/items")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response add(@PathParam("customerId") String customerId, ItemRequest request) {
    try {
      return Response.ok(carts.addItem(customerId, request.sku(), request.quantity())).build();
    } catch (IllegalArgumentException ex) {
      return ApiError.of(400, "BAD_REQUEST", ex.getMessage());
    } catch (IllegalStateException ex) {
      return ApiError.of(404, "NOT_FOUND", ex.getMessage());
    }
  }

  @DELETE
  @Path("/carts/{customerId}/items/{sku}")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> remove(@PathParam("customerId") String customerId, @PathParam("sku") String sku) {
    return carts.removeItem(customerId, sku);
  }

  @POST
  @Path("/carts/{customerId}/coupon")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, Object> coupon(@PathParam("customerId") String customerId, CouponRequest request) {
    return carts.applyCoupon(customerId, request == null ? "" : request.code());
  }

  @DELETE
  @Path("/carts/{customerId}")
  public Response clear(@PathParam("customerId") String customerId) {
    carts.clear(customerId);
    return Response.noContent().build();
  }

  @GET
  @Path("/info")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> info() {
    return Map.of(
        "component", "cart",
        "framework", "quarkus 3.40.1",
        "gc", gc(),
        "java", System.getProperty("java.version"),
        "pod", PodIdentity.pod(),
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

  public record ItemRequest(String sku, int quantity) {}

  public record CouponRequest(String code) {}
}
