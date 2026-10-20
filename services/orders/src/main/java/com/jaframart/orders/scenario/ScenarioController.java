package com.jaframart.orders.scenario;

import com.jaframart.orders.api.ApiError;
import com.jaframart.orders.notify.FulfillmentNotifier;
import com.jaframart.scenario.ScenarioRegistry;
import com.jaframart.scenario.ScenarioStatus;
import com.jaframart.scenario.StartRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/scenarios")
public class ScenarioController implements AutoCloseable {
  private final ScenarioRegistry registry;

  public ScenarioController(FulfillmentNotifier notifier) {
    this.registry = new ScenarioRegistry("orders", List.of(new ThreadsBurstPools(notifier)));
  }

  @GetMapping
  public List<ScenarioStatus> list() {
    return registry.list();
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> get(@PathVariable String id) {
    try {
      return ResponseEntity.ok(registry.get(id));
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @PostMapping("/{id}/start")
  public ResponseEntity<?> start(@PathVariable String id, @RequestBody(required = false) StartRequest request) {
    try {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(registry.start(id, request));
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @PostMapping("/{id}/stop")
  public ResponseEntity<?> stop(@PathVariable String id) {
    try {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(registry.stop(id));
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @PostMapping("/{id}/reset")
  public ResponseEntity<?> reset(@PathVariable String id) {
    try {
      return ResponseEntity.ok(registry.reset(id));
    } catch (ScenarioRegistry.ControlException ex) {
      return error(ex);
    }
  }

  @PostMapping("/reset-all")
  public List<ScenarioStatus> resetAll() {
    return registry.resetAll();
  }

  @Override
  @PreDestroy
  public void close() {
    registry.close();
  }

  private static ResponseEntity<?> error(ScenarioRegistry.ControlException ex) {
    HttpStatus status = switch (ex.kind()) {
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
      case CONFLICT -> HttpStatus.CONFLICT;
    };
    String code = switch (ex.kind()) {
      case NOT_FOUND -> "NOT_FOUND";
      case BAD_REQUEST -> "BAD_REQUEST";
      case CONFLICT -> "CONFLICT";
    };
    return ApiError.of(status, code, ex.getMessage());
  }
}
