package org.arguslog.api.alerts.adapter.in.web;

import java.net.URI;
import java.util.List;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertDestinationRequest;
import org.arguslog.api.alerts.adapter.in.web.dto.AlertDestinationResponse;
import org.arguslog.api.alerts.application.AlertDestinationUseCase;
import org.arguslog.api.alerts.application.AlertDestinationUseCase.InvalidDestinationConfigException;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;
import org.arguslog.api.security.AccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
@RequestMapping(value = "/api/v1/orgs/{orgId}/alert-destinations", produces = MediaType.APPLICATION_JSON_VALUE)
public class AlertDestinationController {

  private final AlertDestinationUseCase useCase;

  public AlertDestinationController(AlertDestinationUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public List<AlertDestinationResponse> list(@PathVariable long orgId) {
    return useCase.list(orgId).stream().map(AlertDestinationResponse::from).toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AlertDestinationResponse> create(
      @PathVariable long orgId, @RequestBody AlertDestinationRequest body) {
    DestinationKind kind = parseKind(body.kind());
    AlertDestination created = useCase.create(orgId, kind, body.name(), body.config());
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(AlertDestinationResponse.from(created));
  }

  @GetMapping("/{id}")
  public AlertDestinationResponse get(@PathVariable long orgId, @PathVariable long id) {
    return useCase
        .get(orgId, id)
        .map(AlertDestinationResponse::from)
        .orElseThrow(() -> AccessException.notFound(id));
  }

  @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public AlertDestinationResponse update(
      @PathVariable long orgId, @PathVariable long id, @RequestBody AlertDestinationRequest body) {
    return useCase
        .update(orgId, id, body.name(), body.config())
        .map(AlertDestinationResponse::from)
        .orElseThrow(() -> AccessException.notFound(id));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long orgId, @PathVariable long id) {
    if (!useCase.delete(orgId, id)) {
      throw AccessException.notFound(id);
    }
    return ResponseEntity.noContent().build();
  }

  private static DestinationKind parseKind(String raw) {
    try {
      return DestinationKind.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidDestinationConfigException(
          "kind must be one of: telegram, email, slack, webhook");
    }
  }

  @ExceptionHandler(InvalidDestinationConfigException.class)
  ResponseEntity<ProblemDetail> handleBadConfig(InvalidDestinationConfigException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid alert destination");
    body.setType(URI.create("https://arguslog.dev/problems/invalid-alert-destination"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
