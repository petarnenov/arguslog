package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.arguslog.api.adapter.in.web.dto.OrgRequest;
import org.arguslog.api.adapter.in.web.dto.OrgResponse;
import org.arguslog.api.application.OrgUseCase;
import org.arguslog.api.application.OrgUseCase.DuplicateOrgException;
import org.arguslog.api.application.OrgUseCase.InvalidOrgException;
import org.arguslog.api.application.OrgUseCase.OrgAccessDeniedException;
import org.arguslog.api.application.OrgUseCase.OrgQuotaExceededException;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.domain.Org;
import org.arguslog.api.security.AccessException;
import org.arguslog.api.security.AuthActor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/orgs", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrgController {

  private final OrgUseCase useCase;

  public OrgController(OrgUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public List<OrgResponse> listMine() {
    UUID userId = AuthActor.currentUserId();
    return useCase.listForUser(userId).stream().map(OrgResponse::from).toList();
  }

  /**
   * Creates an org and adds the caller as owner. The user row is provisioned/refreshed earlier in
   * the request by {@code JwtUserSyncInterceptor} for JWT auth; PAT auth requires an existing user
   * row by definition, so no per-request sync is needed here.
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OrgResponse> create(@RequestBody OrgRequest body) {
    PatScopeGuard.require(PatScope.ORGS_WRITE);
    UUID userId = AuthActor.currentUserId();
    Org created = useCase.create(userId, body.name());
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(OrgResponse.from(created));
  }

  /**
   * Hard-deletes an org. Owner-only; non-owners get 403 (vs 404 from non-members earlier in the
   * filter chain — non-existence and not-a-member already collapse there). Cascades remove every
   * project/issue/event/key/destination/rule/release.
   */
  @DeleteMapping("/{orgId}")
  public ResponseEntity<Void> delete(@PathVariable long orgId) {
    PatScopeGuard.require(PatScope.ORGS_WRITE);
    UUID actorId = AuthActor.currentUserId();
    if (!useCase.delete(actorId, orgId)) {
      throw AccessException.notFound(orgId);
    }
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(InvalidOrgException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidOrgException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid org");
    body.setType(URI.create("https://arguslog.org/problems/invalid-org"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(DuplicateOrgException.class)
  ResponseEntity<ProblemDetail> handleDuplicate(DuplicateOrgException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setTitle("Duplicate org");
    body.setType(URI.create("https://arguslog.org/problems/duplicate-org"));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(OrgAccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleForbidden(OrgAccessDeniedException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    body.setTitle("Forbidden");
    body.setType(URI.create("https://arguslog.org/problems/org-access-denied"));
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(OrgQuotaExceededException.class)
  ResponseEntity<ProblemDetail> handleOrgQuotaExceeded(OrgQuotaExceededException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    body.setTitle("Org cap exceeded");
    body.setType(URI.create("https://arguslog.org/problems/org-cap-exceeded"));
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
