package org.arguslog.api.admin.adapter.in.web;

import java.net.URI;
import java.util.UUID;
import org.arguslog.api.admin.PlatformAdminGuard;
import org.arguslog.api.admin.PlatformAdminGuard.AdminAccessDeniedException;
import org.arguslog.api.admin.adapter.in.web.dto.AdminAuditResponse;
import org.arguslog.api.admin.adapter.in.web.dto.AdminOrgResponse;
import org.arguslog.api.admin.adapter.in.web.dto.AdminPageResponse;
import org.arguslog.api.admin.adapter.in.web.dto.AdminStatsResponse;
import org.arguslog.api.admin.adapter.in.web.dto.AdminUserResponse;
import org.arguslog.api.admin.adapter.in.web.dto.GrantBonusRequest;
import org.arguslog.api.admin.application.AdminGrantService;
import org.arguslog.api.admin.application.port.AdminQueryPort;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-administrator endpoints. Every method runs {@link PlatformAdminGuard#requireAdmin()}
 * first; only an interactive (JWT) login from an email in {@code arguslog.platform-admins} gets
 * past. State-changing methods (POST grant, DELETE revoke) write an entry to {@code
 * admin_audit_log} via {@link AdminGrantService}.
 */
@RestController
@RequestMapping(value = "/api/v1/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminController {

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 200;

  private final PlatformAdminGuard guard;
  private final AdminQueryPort port;
  private final AdminGrantService grants;

  public AdminController(PlatformAdminGuard guard, AdminQueryPort port, AdminGrantService grants) {
    this.guard = guard;
    this.port = port;
    this.grants = grants;
  }

  @GetMapping("/stats")
  public AdminStatsResponse stats() {
    guard.requireAdmin();
    return AdminStatsResponse.from(port.stats());
  }

  @GetMapping("/users")
  public AdminPageResponse<AdminUserResponse> users(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit) {
    guard.requireAdmin();
    int off = clampOffset(offset);
    int lim = clampLimit(limit);
    return new AdminPageResponse<>(
        port.listUsers(q, off, lim).stream().map(AdminUserResponse::from).toList(),
        port.countUsers(q),
        off,
        lim);
  }

  @GetMapping("/orgs")
  public AdminPageResponse<AdminOrgResponse> orgs(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit) {
    guard.requireAdmin();
    int off = clampOffset(offset);
    int lim = clampLimit(limit);
    return new AdminPageResponse<>(
        port.listOrgs(q, off, lim).stream().map(AdminOrgResponse::from).toList(),
        port.countOrgs(q),
        off,
        lim);
  }

  @GetMapping("/orgs/{orgId}")
  public AdminOrgResponse org(@PathVariable long orgId) {
    guard.requireAdmin();
    return port.getOrg(orgId)
        .map(AdminOrgResponse::from)
        .orElseThrow(() -> new OrgNotFoundException("Organization " + orgId + " not found."));
  }

  @PostMapping(value = "/orgs/{orgId}/grant", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> grant(@PathVariable long orgId, @RequestBody GrantBonusRequest body) {
    String adminEmail = guard.requireAdmin();
    UUID adminUser = AuthActor.currentUserId();
    grants.grant(orgId, body.tier(), body.months(), body.reason(), adminUser, adminEmail);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/orgs/{orgId}/grant")
  public ResponseEntity<Void> revoke(@PathVariable long orgId) {
    String adminEmail = guard.requireAdmin();
    UUID adminUser = AuthActor.currentUserId();
    grants.revoke(orgId, adminUser, adminEmail);
    return ResponseEntity.noContent().build();
  }

  /**
   * Per-user grant — the V26+ direct surface. Targets a user instead of an org, so the bonus tier
   * automatically covers every org that user owns (per-user billing). The old org-scoped grant
   * endpoint above stays around as a compat shim; new code paths should call this one.
   */
  @PostMapping(value = "/users/{userId}/grant", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> grantUser(
      @PathVariable UUID userId, @RequestBody GrantBonusRequest body) {
    String adminEmail = guard.requireAdmin();
    UUID adminUser = AuthActor.currentUserId();
    grants.grantToUser(userId, body.tier(), body.months(), body.reason(), adminUser, adminEmail);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/users/{userId}/grant")
  public ResponseEntity<Void> revokeUser(@PathVariable UUID userId) {
    String adminEmail = guard.requireAdmin();
    UUID adminUser = AuthActor.currentUserId();
    grants.revokeUser(userId, adminUser, adminEmail);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/audit")
  public AdminPageResponse<AdminAuditResponse> audit(
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit) {
    guard.requireAdmin();
    int off = clampOffset(offset);
    int lim = clampLimit(limit);
    return new AdminPageResponse<>(
        port.listAudit(off, lim).stream().map(AdminAuditResponse::from).toList(),
        port.countAudit(),
        off,
        lim);
  }

  // ── error handlers ────────────────────────────────────────────────────────

  @ExceptionHandler(AdminAccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleForbidden(AdminAccessDeniedException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    body.setTitle("Forbidden");
    body.setType(URI.create("https://arguslog.org/problems/admin-forbidden"));
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid admin request");
    body.setType(URI.create("https://arguslog.org/problems/admin-invalid"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(OrgNotFoundException.class)
  ResponseEntity<ProblemDetail> handleNotFound(OrgNotFoundException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    body.setTitle("Org not found");
    body.setType(URI.create("https://arguslog.org/problems/org-not-found"));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private static int clampOffset(Integer offset) {
    if (offset == null || offset < 0) return 0;
    return offset;
  }

  private static int clampLimit(Integer limit) {
    if (limit == null || limit <= 0) return DEFAULT_LIMIT;
    return Math.min(limit, MAX_LIMIT);
  }

  static final class OrgNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    OrgNotFoundException(String message) {
      super(message);
    }
  }
}
