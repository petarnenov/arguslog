package org.arguslog.api.auth.adapter.in.web;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.auth.adapter.in.web.dto.PatRequest;
import org.arguslog.api.auth.adapter.in.web.dto.PatResponse;
import org.arguslog.api.auth.application.PatUseCase;
import org.arguslog.api.auth.application.PatUseCase.InvalidPatException;
import org.arguslog.api.auth.application.PatUseCase.Issued;
import org.arguslog.api.auth.domain.PatScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "My tokens" — every endpoint here implicitly scopes to the authenticated user (read from the
 * security context). PATs are personal credentials so org-membership checks never enter the
 * picture.
 */
@RestController
@RequestMapping(value = "/api/v1/me/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
public class MeTokensController {

  private final PatUseCase useCase;

  public MeTokensController(PatUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public List<PatResponse> list() {
    return useCase.list(currentUserId()).stream().map(PatResponse::from).toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<PatResponse> create(@RequestBody PatRequest body) {
    PatRequest req = body == null ? new PatRequest(null, null, null) : body;
    Set<PatScope> scopes = parseScopes(req.scopes());
    Issued issued = useCase.create(currentUserId(), req.name(), req.expiresAt(), scopes);
    return ResponseEntity.created(URI.create(String.valueOf(issued.token().id())))
        .body(PatResponse.fromIssued(issued.token(), issued.plaintext()));
  }

  // null wire input → null domain set ("implicit-all", back-compat with pre-V12 callers).
  // An explicit list pins the token to those scopes; unknown wire strings 400 here so a typo
  // can't silently mint an over-scoped token.
  private static Set<PatScope> parseScopes(List<String> wire) {
    if (wire == null) return null;
    Set<PatScope> out = new LinkedHashSet<>();
    for (String w : wire) {
      try {
        out.add(PatScope.fromWire(w));
      } catch (IllegalArgumentException e) {
        throw new InvalidPatException("Unknown scope: " + w);
      }
    }
    return out;
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    if (!useCase.revoke(currentUserId(), id)) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(InvalidPatException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidPatException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid token");
    body.setType(URI.create("https://arguslog.dev/problems/invalid-pat"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private static UUID currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw new IllegalStateException("MeTokensController reached without an Authentication");
    }
    return UUID.fromString(auth.getName());
  }
}
