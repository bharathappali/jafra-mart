package com.jaframart.payments.api;

import com.jaframart.payments.gateway.PaymentService;
import com.jaframart.scenario.PodIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
public class PaymentsResource {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final PaymentService payments;

  @Inject
  public PaymentsResource(PaymentService payments) {
    this.payments = payments;
  }

  @POST
  @Path("/payments/authorize")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @RunOnVirtualThread
  public Response authorize(AuthorizeRequest request) {
    try {
      if (request == null) {
        return ApiError.of(400, "BAD_REQUEST", "body required");
      }
      return Response.ok(payments.authorize(
          request.orderNumber(),
          request.customerId(),
          request.amountCents(),
          request.currency(),
          request.cardToken()).toMap()).build();
    } catch (IllegalArgumentException ex) {
      return ApiError.of(400, "BAD_REQUEST", ex.getMessage());
    }
  }

  @GET
  @Path("/payments/{paymentId}")
  @Produces(MediaType.APPLICATION_JSON)
  @RunOnVirtualThread
  public Response get(@PathParam("paymentId") String paymentId) {
    return payments.get(paymentId)
        .map(record -> Response.ok(record.toMap()).build())
        .orElseGet(() -> ApiError.of(404, "NOT_FOUND", "payment not found"));
  }

  @GET
  @Path("/info")
  @Produces(MediaType.APPLICATION_JSON)
  @RunOnVirtualThread
  public Map<String, String> info() {
    return Map.of(
        "component", "payments",
        "framework", "quarkus 3.40.1",
        "gc", gc(),
        "java", System.getProperty("java.version"),
        "pod", PodIdentity.pod(),
        "startedAt", ISO.format(STARTED));
  }

  static String gc() {
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      String name = bean.getName();
      if (name.startsWith("ZGC")) {
        return "ZGC";
      }
      if (name.startsWith("G1")) {
        return "G1";
      }
      if (name.startsWith("PS")) {
        return "Parallel";
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

  public record AuthorizeRequest(
      String orderNumber,
      String customerId,
      long amountCents,
      String currency,
      String cardToken) {}
}
