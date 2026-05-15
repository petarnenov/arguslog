package org.arguslog.api.alerts.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleRequest;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertRuleResponse;
import org.arguslog.api.alerts.application.AlertRuleUseCase;
import org.arguslog.api.alerts.application.AlertRuleUseCase.InvalidAlertRuleException;
import org.arguslog.api.alerts.domain.AlertRule;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.security.AccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/v1/projects/{projectId}/alert-rules",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class AlertRuleController {

  private final AlertRuleUseCase useCase;
  private final ObjectMapper mapper;

  public AlertRuleController(AlertRuleUseCase useCase, ObjectMapper mapper) {
    this.useCase = useCase;
    this.mapper = mapper;
  }

  @GetMapping
  public List<AlertRuleResponse> list(@PathVariable long projectId) {
    return useCase.list(projectId).stream().map(r -> AlertRuleResponse.from(r, mapper)).toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AlertRuleResponse> create(
      @PathVariable long projectId, @RequestBody AlertRuleRequest body) {
    PatScopeGuard.require(PatScope.ALERTS_WRITE);
    AlertRule created =
        useCase.create(
            projectId,
            body.name(),
            body.conditions(),
            body.actions(),
            body.throttleOrDefault(),
            body.enabledOrDefault());
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(AlertRuleResponse.from(created, mapper));
  }

  @GetMapping("/{id}")
  public AlertRuleResponse get(@PathVariable long projectId, @PathVariable long id) {
    return useCase
        .get(projectId, id)
        .map(r -> AlertRuleResponse.from(r, mapper))
        .orElseThrow(() -> AccessException.notFound(id));
  }

  @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public AlertRuleResponse update(
      @PathVariable long projectId, @PathVariable long id, @RequestBody AlertRuleRequest body) {
    PatScopeGuard.require(PatScope.ALERTS_WRITE);
    return useCase
        .update(
            projectId,
            id,
            body.name(),
            body.conditions(),
            body.actions(),
            body.throttleOrDefault(),
            body.enabledOrDefault())
        .map(r -> AlertRuleResponse.from(r, mapper))
        .orElseThrow(() -> AccessException.notFound(id));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long projectId, @PathVariable long id) {
    PatScopeGuard.require(PatScope.ALERTS_WRITE);
    if (!useCase.delete(projectId, id)) {
      throw AccessException.notFound(id);
    }
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(InvalidAlertRuleException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidAlertRuleException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid alert rule");
    body.setType(URI.create("https://arguslog.org/problems/invalid-alert-rule"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  /**
   * Jackson can throw before the request reaches the validator — e.g. {@code "level":"error"}
   * (string where an object is expected). Surface as 400 with the same problem+json shape rather
   * than a 500 from the upstream dispatcher servlet.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "request body does not match the alert rule schema — see the OpenAPI spec for"
                + " AlertRuleRequest");
    body.setTitle("Malformed alert rule payload");
    body.setType(URI.create("https://arguslog.org/problems/invalid-alert-rule"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
