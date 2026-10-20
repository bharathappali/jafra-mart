package com.jaframart.pricing.scenario;

import com.jaframart.pricing.api.ApiError;
import com.jaframart.pricing.engine.CampaignEngine;
import com.jaframart.pricing.engine.PipelineEngine;
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
  private final CampaignEngine campaigns;

  @Inject
  public ScenarioResource(CampaignEngine campaigns, PipelineEngine pipelines) {
    this.campaigns = campaigns;
    this.registry = new ScenarioRegistry("pricing",
        List.of(new MetaspaceCampaignClasses(campaigns), new JitDeoptCodecache(pipelines)));
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
      if ("S08".equals(id)) {
        int hold = request != null && request.holdSeconds() != null ? request.holdSeconds() : 180;
        campaigns.setHoldSeconds(hold);
      }
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
