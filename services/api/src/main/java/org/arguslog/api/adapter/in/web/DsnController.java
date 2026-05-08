package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.util.List;
import org.arguslog.api.adapter.in.web.dto.DsnResponse;
import org.arguslog.api.adapter.in.web.dto.DsnSummaryResponse;
import org.arguslog.api.application.DsnUseCase;
import org.arguslog.api.application.DsnUseCase.DsnAlreadyRevokedException;
import org.arguslog.api.application.DsnUseCase.DsnNotFoundException;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.domain.Dsn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/v1/projects/{projectId}/keys",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class DsnController {

  private final DsnUseCase useCase;
  private final String ingestHost;

  public DsnController(
      DsnUseCase useCase,
      @Value("${arguslog.ingest.public-host:http://localhost:8080}") String ingestHost) {
    this.useCase = useCase;
    this.ingestHost = ingestHost;
  }

  /**
   * Listing returns DSN metadata only — never the full {@code dsn} string. Mirrors the GitHub
   * PAT model where the secret-shaped value is shown exactly once at creation time. Use the
   * {@code POST} endpoint to mint a new key + see its DSN, or {@code DELETE} to revoke.
   */
  @GetMapping
  public List<DsnSummaryResponse> list(@PathVariable long projectId) {
    return useCase.list(projectId).stream().map(DsnSummaryResponse::from).toList();
  }

  /** Mints a new key. The returned {@link DsnResponse} carries the full DSN once. */
  @PostMapping
  public ResponseEntity<DsnResponse> create(@PathVariable long projectId) {
    PatScopeGuard.require(PatScope.PROJECTS_WRITE);
    Dsn created = useCase.create(projectId);
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(DsnResponse.from(created, ingestHost));
  }

  /** Revoke (soft-delete) a key. Returns 204 on success. */
  @DeleteMapping("/{keyId}")
  public ResponseEntity<Void> revoke(@PathVariable long projectId, @PathVariable long keyId) {
    PatScopeGuard.require(PatScope.PROJECTS_WRITE);
    useCase.revoke(projectId, keyId);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(DsnNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(DsnNotFoundException ex) {
    ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    body.setType(URI.create("https://arguslog.org/problems/dsn-not-found"));
    body.setTitle("DSN not found");
    body.setDetail(ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  @ExceptionHandler(DsnAlreadyRevokedException.class)
  public ResponseEntity<ProblemDetail> handleAlreadyRevoked(DsnAlreadyRevokedException ex) {
    ProblemDetail body = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    body.setType(URI.create("https://arguslog.org/problems/dsn-already-revoked"));
    body.setTitle("DSN already revoked");
    body.setDetail(ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}
