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
import org.arguslog.api.domain.Org;
import org.arguslog.api.security.AccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
  public List<OrgResponse> listMine(JwtAuthenticationToken token) {
    UUID userId = parseSubject(token);
    return useCase.listForUser(userId).stream().map(OrgResponse::from).toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<OrgResponse> create(
      @RequestBody OrgRequest body, JwtAuthenticationToken token) {
    Jwt jwt = token.getToken();
    UUID userId = parseSubject(token);
    String email = jwt.getClaimAsString("email");
    String displayName =
        firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username"));
    Org created = useCase.create(userId, email, displayName, body.name());
    return ResponseEntity.created(URI.create(String.valueOf(created.id())))
        .body(OrgResponse.from(created));
  }

  /**
   * Hard-deletes an org. Owner-only; non-owners get 403 (vs 404 from non-members earlier in the
   * filter chain — non-existence and not-a-member already collapse there). Cascades remove every
   * project/issue/event/key/destination/rule/release.
   */
  @DeleteMapping("/{orgId}")
  public ResponseEntity<Void> delete(@PathVariable long orgId, JwtAuthenticationToken token) {
    UUID actorId = parseSubject(token);
    if (!useCase.delete(actorId, orgId)) {
      throw AccessException.notFound(orgId);
    }
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(InvalidOrgException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidOrgException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid org");
    body.setType(URI.create("https://arguslog.dev/problems/invalid-org"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(DuplicateOrgException.class)
  ResponseEntity<ProblemDetail> handleDuplicate(DuplicateOrgException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setTitle("Duplicate org");
    body.setType(URI.create("https://arguslog.dev/problems/duplicate-org"));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(OrgAccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleForbidden(OrgAccessDeniedException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    body.setTitle("Forbidden");
    body.setType(URI.create("https://arguslog.dev/problems/org-access-denied"));
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private static UUID parseSubject(JwtAuthenticationToken token) {
    try {
      return UUID.fromString(token.getName());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "JWT subject is not a UUID — Keycloak realm misconfigured?", e);
    }
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return null;
  }
}
