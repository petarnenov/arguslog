package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.arguslog.api.adapter.in.web.dto.MemberInviteRequest;
import org.arguslog.api.adapter.in.web.dto.MemberResponse;
import org.arguslog.api.adapter.in.web.dto.MemberRoleUpdateRequest;
import org.arguslog.api.application.MemberUseCase;
import org.arguslog.api.application.MemberUseCase.DuplicateMemberException;
import org.arguslog.api.application.MemberUseCase.InvalidMemberException;
import org.arguslog.api.application.MemberUseCase.LastOwnerException;
import org.arguslog.api.application.MemberUseCase.MemberAccessDeniedException;
import org.arguslog.api.application.MemberUseCase.MemberNotFoundException;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.domain.Member;
import org.arguslog.api.security.AuthActor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Org-member management. {@link org.arguslog.api.security.OrgAccessGuard} already enforces that the
 * caller is at least a member of {@code orgId} for any URL under /api/v1/orgs/*∕** — owner-only
 * checks for write operations live in {@link MemberUseCase}.
 */
@RestController
@RequestMapping(value = "/api/v1/orgs/{orgId}/members", produces = MediaType.APPLICATION_JSON_VALUE)
public class MemberController {

  private final MemberUseCase useCase;

  public MemberController(MemberUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public List<MemberResponse> list(@PathVariable long orgId) {
    return useCase.list(orgId).stream().map(MemberResponse::from).toList();
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<MemberResponse> invite(
      @PathVariable long orgId, @RequestBody MemberInviteRequest body) {
    PatScopeGuard.require(PatScope.ORGS_WRITE);
    UUID actorId = AuthActor.currentUserId();
    Member created = useCase.invite(actorId, orgId, body.email(), body.role());
    return ResponseEntity.created(URI.create(created.userId().toString()))
        .body(MemberResponse.from(created));
  }

  @PatchMapping(value = "/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public MemberResponse changeRole(
      @PathVariable long orgId,
      @PathVariable UUID userId,
      @RequestBody MemberRoleUpdateRequest body) {
    PatScopeGuard.require(PatScope.ORGS_WRITE);
    UUID actorId = AuthActor.currentUserId();
    return MemberResponse.from(useCase.changeRole(actorId, orgId, userId, body.role()));
  }

  @DeleteMapping("/{userId}")
  public ResponseEntity<Void> remove(@PathVariable long orgId, @PathVariable UUID userId) {
    PatScopeGuard.require(PatScope.ORGS_WRITE);
    UUID actorId = AuthActor.currentUserId();
    useCase.remove(actorId, orgId, userId);
    return ResponseEntity.noContent().build();
  }

  // ── exception → ProblemDetail mapping ────────────────────────────────────

  @ExceptionHandler(InvalidMemberException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidMemberException e) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid member", "invalid-member", e.getMessage());
  }

  @ExceptionHandler(DuplicateMemberException.class)
  ResponseEntity<ProblemDetail> handleDuplicate(DuplicateMemberException e) {
    return problem(HttpStatus.CONFLICT, "Already a member", "duplicate-member", e.getMessage());
  }

  @ExceptionHandler(LastOwnerException.class)
  ResponseEntity<ProblemDetail> handleLastOwner(LastOwnerException e) {
    return problem(HttpStatus.CONFLICT, "Last owner", "last-owner", e.getMessage());
  }

  @ExceptionHandler(MemberAccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleForbidden(MemberAccessDeniedException e) {
    return problem(HttpStatus.FORBIDDEN, "Forbidden", "member-access-denied", e.getMessage());
  }

  @ExceptionHandler(MemberNotFoundException.class)
  ResponseEntity<ProblemDetail> handleNotFound(MemberNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "Member not found", "member-not-found", e.getMessage());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String title, String typeSlug, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(title);
    body.setType(URI.create("https://arguslog.dev/problems/" + typeSlug));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
