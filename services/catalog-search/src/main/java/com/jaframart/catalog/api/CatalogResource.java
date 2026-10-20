package com.jaframart.catalog.api;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Product;
import com.jaframart.catalog.search.SearchService;
import com.jaframart.catalog.thumb.ThumbnailCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
public class CatalogResource {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Instant STARTED = Instant.now();

  private final SearchService search;
  private final ThumbnailCache thumbnails;
  private volatile boolean flashActive;

  @Inject
  public CatalogResource(SearchService search, ThumbnailCache thumbnails) {
    this.search = search;
    this.thumbnails = thumbnails;
  }

  @GET
  @Path("/products")
  @Produces(MediaType.APPLICATION_JSON)
  public Response products(@QueryParam("page") @DefaultValue("0") int page,
                           @QueryParam("size") @DefaultValue("24") int size,
                           @QueryParam("category") String category) {
    if (page < 0 || size < 1 || size > 100) {
      return ApiError.of(400, "BAD_REQUEST", "page must be >= 0 and size must be 1..100");
    }
    List<Product> filtered = new ArrayList<>();
    for (Product product : CatalogData.products()) {
      if (category == null || category.isBlank() || category.equals(product.category())) {
        filtered.add(product);
      }
    }
    int from = Math.min(filtered.size(), page * size);
    int to = Math.min(filtered.size(), from + size);
    return Response.ok(Map.of(
        "page", page,
        "size", size,
        "total", filtered.size(),
        "items", List.copyOf(filtered.subList(from, to)))).build();
  }

  @GET
  @Path("/products/{sku}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response product(@PathParam("sku") String sku) {
    Product product = CatalogData.product(sku);
    if (product == null) {
      return ApiError.of(404, "NOT_FOUND", "unknown sku " + sku);
    }
    return Response.ok(product).build();
  }

  @GET
  @Path("/search")
  @Produces(MediaType.APPLICATION_JSON)
  public Response search(@QueryParam("q") @DefaultValue("") String q,
                         @QueryParam("limit") @DefaultValue("20") int limit,
                         @QueryParam("fuzzy") @DefaultValue("false") boolean fuzzy) {
    if (limit < 1 || limit > 100) {
      return ApiError.of(400, "BAD_REQUEST", "limit must be 1..100");
    }
    List<Product> items = fuzzy ? search.fuzzy(q, limit) : search.exact(q, limit);
    return Response.ok(Map.of("query", q, "fuzzy", fuzzy, "count", items.size(), "items", items)).build();
  }

  @GET
  @Path("/flash")
  @Produces(MediaType.APPLICATION_JSON)
  public Flash flash() {
    return new Flash(flashActive, CatalogData.FLASH_SKU, 20);
  }

  @PUT
  @Path("/flash")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Flash updateFlash(FlashUpdate update) {
    flashActive = update != null && update.active();
    return flash();
  }

  @GET
  @Path("/products/{sku}/thumbnail")
  @Produces("image/png")
  public Response thumbnail(@PathParam("sku") String sku, @QueryParam("size") @DefaultValue("128") int size) {
    if (size != 64 && size != 128 && size != 256) {
      return ApiError.of(400, "BAD_REQUEST", "size must be 64, 128, or 256");
    }
    if (CatalogData.product(sku) == null) {
      return ApiError.of(404, "NOT_FOUND", "unknown sku " + sku);
    }
    return Response.ok(thumbnails.png(sku, size)).type("image/png").build();
  }

  @GET
  @Path("/info")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> info() {
    return Map.of(
        "component", "catalog-search",
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

  public record Flash(boolean active, String sku, int discountPercent) {}

  public record FlashUpdate(boolean active) {}
}
