package com.jaframart.payments.scenario;

import com.jaframart.payments.api.ApiError;
import com.jaframart.payments.gateway.PaymentService;
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
  private final PaymentService payments;

  @Inject
  public ScenarioResource(PaymentService payments) {
    this.payments = payments;
    this.registry = new ScenarioRegistry("payments", List.of(
        new SocketSlowPayments(payments),
        new VthreadPinning(payments)));
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
      payments.resetScenarios();
      return Response.ok(registry.reset(id)).build();
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @POST
  @Path("reset-all")
  public List<ScenarioStatus> resetAll() {
    payments.resetScenarios();
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
