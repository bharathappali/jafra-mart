package com.jaframart.cart.scenario;

import com.jaframart.cart.api.ApiError;
import com.jaframart.cart.coupon.CouponValidator;
import com.jaframart.scenario.ScenarioRegistry;
import com.jaframart.scenario.ScenarioStatus;
import com.jaframart.scenario.StartRequest;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@ApplicationScoped
@Path("/internal/scenarios")
@Produces(MediaType.APPLICATION_JSON)
public class ScenarioResource implements AutoCloseable {
  private final ScenarioRegistry registry;

  @Inject
  public ScenarioResource(CouponValidator validator) {
    this.registry = new ScenarioRegistry("cart", List.of(new ExceptionsCouponStorm(validator)));
  }

  @GET
  public List<ScenarioStatus> list() {
    return registry.list();
  }

  @GET
  @Path("{id}")
  public Response get(@PathParam("id") String id) {
    try {
      return Response.ok(registry.get(id)).build();
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @POST
  @Path("{id}/start")
  public Response start(@PathParam("id") String id, StartRequest request) {
    try {
      return Response.status(202).entity(registry.start(id, request)).build();
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @POST
  @Path("{id}/stop")
  public Response stop(@PathParam("id") String id) {
    try {
      return Response.status(202).entity(registry.stop(id)).build();
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @POST
  @Path("{id}/reset")
  public Response reset(@PathParam("id") String id) {
    try {
      return Response.ok(registry.reset(id)).build();
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @POST
  @Path("reset-all")
  public List<ScenarioStatus> resetAll() {
    return registry.resetAll();
  }

  @Override
  @PreDestroy
  public void close() {
    registry.close();
  }

  private static Response error(ScenarioRegistry.ControlException ex) {
    int status = switch (ex.kind()) {
      case NOT_FOUND -> 404;
      case BAD_REQUEST -> 400;
      case CONFLICT -> 409;
    };
    String code = switch (ex.kind()) {
      case NOT_FOUND -> "NOT_FOUND";
      case BAD_REQUEST -> "BAD_REQUEST";
      case CONFLICT -> "CONFLICT";
    };
    return ApiError.of(status, code, ex.getMessage());
  }
}
