package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.arguslog.api.adapter.in.web.dto.EventResponse;
import org.arguslog.api.adapter.in.web.dto.IssueAiAnalysisRequest;
import org.arguslog.api.adapter.in.web.dto.IssueAssigneeRequest;
import org.arguslog.api.adapter.in.web.dto.IssueResponse;
import org.arguslog.api.adapter.in.web.dto.IssueStatusRequest;
import org.arguslog.api.adapter.in.web.dto.PageResponse;
import org.arguslog.api.application.CursorCodec.InvalidCursorException;
import org.arguslog.api.application.GetIssueUseCase;
import org.arguslog.api.application.IssueTriageUseCase;
import org.arguslog.api.application.IssueTriageUseCase.InvalidAiAnalysisException;
import org.arguslog.api.application.IssueTriageUseCase.InvalidAssigneeException;
import org.arguslog.api.application.ListIssueEventsUseCase;
import org.arguslog.api.application.ListIssuesUseCase;
import org.arguslog.api.application.ListIssuesUseCase.AssigneeFilter;
import org.arguslog.api.application.ListIssuesUseCase.Query;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.security.AccessException;
import org.arguslog.api.security.AuthActor;
import org.arguslog.api.security.OrgContext;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/v1/projects/{projectId}/issues",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class IssueController {

  private final ListIssuesUseCase listIssues;
  private final GetIssueUseCase getIssue;
  private final ListIssueEventsUseCase listEvents;
  private final IssueTriageUseCase triage;

  public IssueController(
      ListIssuesUseCase listIssues,
      GetIssueUseCase getIssue,
      ListIssueEventsUseCase listEvents,
      IssueTriageUseCase triage) {
    this.listIssues = listIssues;
    this.getIssue = getIssue;
    this.listEvents = listEvents;
    this.triage = triage;
  }

  @GetMapping
  public PageResponse<IssueResponse> list(
      @PathVariable long projectId,
      @RequestParam(value = "status", required = false) String statusParam,
      @RequestParam(value = "level", required = false) String levelParam,
      @RequestParam(value = "q", required = false) String searchParam,
      @RequestParam(value = "assignee", required = false) String assigneeParam,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {

    Optional<Issue.Status> status = parseStatus(statusParam);
    Optional<Issue.Level> level = parseLevel(levelParam);
    Optional<String> searchText =
        Optional.ofNullable(searchParam).map(String::trim).filter(s -> !s.isEmpty());
    Optional<AssigneeFilter> assignee = parseAssignee(assigneeParam);
    var page =
        listIssues.list(
            new Query(
                projectId,
                status,
                level,
                searchText,
                assignee,
                Optional.ofNullable(cursor),
                limit));
    List<IssueResponse> data = page.issues().stream().map(IssueResponse::from).toList();
    return PageResponse.of(data, page.nextCursor().orElse(null));
  }

  @GetMapping("/{issueId}")
  public IssueResponse getOne(@PathVariable long projectId, @PathVariable long issueId) {
    return getIssue
        .get(projectId, issueId)
        .map(IssueResponse::from)
        .orElseThrow(() -> AccessException.notFound(issueId));
  }

  @GetMapping("/{issueId}/events")
  public PageResponse<EventResponse> listEvents(
      @PathVariable long projectId,
      @PathVariable long issueId,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {

    var page =
        listEvents.list(
            new ListIssueEventsUseCase.Query(
                projectId, issueId, Optional.ofNullable(cursor), limit));
    if (page.events().isEmpty() && cursor == null) {
      // ListIssueEventsService returns an empty page for an unknown issue under this project so
      // controller code stays straight-line. Map that to 404 here. (A genuinely empty issue still
      // returns 200 + [], but only after the issue lookup confirms it exists — see service.)
      if (getIssue.get(projectId, issueId).isEmpty()) {
        throw AccessException.notFound(issueId);
      }
    }
    List<EventResponse> data = page.events().stream().map(EventResponse::from).toList();
    return PageResponse.of(data, page.nextCursor().orElse(null));
  }

  /**
   * Status mutation — resolve / ignore / reopen. Any org member can do this; the access guard
   * upstream already verifies the caller belongs to the project's org. PAT-driven callers need
   * {@link PatScope#ISSUES_WRITE}.
   */
  @PatchMapping(value = "/{issueId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public IssueResponse updateStatus(
      @PathVariable long projectId,
      @PathVariable long issueId,
      @RequestBody IssueStatusRequest body) {
    PatScopeGuard.require(PatScope.ISSUES_WRITE);
    Issue.Status status = parseRequiredStatus(body.status());
    return triage
        .updateStatus(OrgContext.requireCurrent(), projectId, issueId, status)
        .map(IssueResponse::from)
        .orElseThrow(() -> AccessException.notFound(issueId));
  }

  /**
   * Assignee mutation. Pass {@code null} userId to unassign. The assignee MUST be a member of the
   * project's org — otherwise we'd allow outside accounts to be attached to an issue.
   */
  @PatchMapping(value = "/{issueId}/assignee", consumes = MediaType.APPLICATION_JSON_VALUE)
  public IssueResponse updateAssignee(
      @PathVariable long projectId,
      @PathVariable long issueId,
      @RequestBody IssueAssigneeRequest body) {
    PatScopeGuard.require(PatScope.ISSUES_WRITE);
    UUID assignee = body == null ? null : body.userId();
    return triage
        .updateAssignee(OrgContext.requireCurrent(), projectId, issueId, assignee)
        .map(IssueResponse::from)
        .orElseThrow(() -> AccessException.notFound(issueId));
  }

  /**
   * Auto-triage agent's write-back. The hosted Claude agent (Managed Agent or equivalent) is
   * triggered by an Arguslog webhook alert, fetches the issue + recent events via MCP, generates a
   * root-cause analysis, and PATCH-es the markdown body back here. Status and assignee are
   * intentionally untouched — the human still owns the triage decision; this is a suggestion field
   * only. PAT-driven callers need {@link PatScope#ISSUES_WRITE} (the agent's own PAT, scoped to one
   * or more projects).
   */
  @PatchMapping(value = "/{issueId}/ai-analysis", consumes = MediaType.APPLICATION_JSON_VALUE)
  public IssueResponse attachAiAnalysis(
      @PathVariable long projectId,
      @PathVariable long issueId,
      @RequestBody IssueAiAnalysisRequest body) {
    PatScopeGuard.require(PatScope.ISSUES_WRITE);
    if (body == null) {
      throw new InvalidAiAnalysisException("request body required");
    }
    return triage
        .attachAiAnalysis(
            OrgContext.requireCurrent(), projectId, issueId, body.analysis(), body.model())
        .map(IssueResponse::from)
        .orElseThrow(() -> AccessException.notFound(issueId));
  }

  private static Issue.Status parseRequiredStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new BadFilterException(
          "status", "(empty)", "must be one of: unresolved, resolved, ignored");
    }
    try {
      return Issue.Status.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new BadFilterException("status", raw, "must be one of: unresolved, resolved, ignored");
    }
  }

  private static Optional<Issue.Status> parseStatus(String raw) {
    if (raw == null || raw.isBlank()) return Optional.empty();
    try {
      return Optional.of(Issue.Status.fromString(raw));
    } catch (IllegalArgumentException e) {
      throw new BadFilterException("status", raw, "must be one of: unresolved, resolved, ignored");
    }
  }

  /**
   * Decode the {@code ?assignee=} query param. Accepts three shapes: {@code none} →
   * unassigned-only; {@code me} → the caller's user id (handy for "my issues"); {@code <uuid>} →
   * exact match on that user. Empty/missing → no assignee constraint.
   */
  private static Optional<AssigneeFilter> parseAssignee(String raw) {
    if (raw == null) return Optional.empty();
    String value = raw.trim();
    if (value.isEmpty() || "all".equalsIgnoreCase(value)) return Optional.empty();
    if ("none".equalsIgnoreCase(value) || "unassigned".equalsIgnoreCase(value)) {
      return Optional.of(AssigneeFilter.UNASSIGNED);
    }
    if ("me".equalsIgnoreCase(value)) {
      return Optional.of(new AssigneeFilter.User(AuthActor.currentUserId()));
    }
    try {
      return Optional.of(new AssigneeFilter.User(UUID.fromString(value)));
    } catch (IllegalArgumentException e) {
      throw new BadFilterException(
          "assignee", raw, "must be a user UUID, 'me', 'none', or omitted");
    }
  }

  private static Optional<Issue.Level> parseLevel(String raw) {
    if (raw == null || raw.isBlank()) return Optional.empty();
    try {
      return Optional.of(Issue.Level.fromString(raw));
    } catch (IllegalArgumentException e) {
      throw new BadFilterException(
          "level", raw, "must be one of: fatal, error, warning, info, debug");
    }
  }

  @ExceptionHandler(InvalidCursorException.class)
  ResponseEntity<ProblemDetail> handleInvalidCursor(InvalidCursorException e) {
    return problem(400, "Invalid cursor", e.getMessage());
  }

  @ExceptionHandler(BadFilterException.class)
  ResponseEntity<ProblemDetail> handleBadFilter(BadFilterException e) {
    return problem(400, "Invalid filter", e.getMessage());
  }

  @ExceptionHandler(InvalidAssigneeException.class)
  ResponseEntity<ProblemDetail> handleInvalidAssignee(InvalidAssigneeException e) {
    return problem(400, "Invalid assignee", e.getMessage());
  }

  @ExceptionHandler(InvalidAiAnalysisException.class)
  ResponseEntity<ProblemDetail> handleInvalidAiAnalysis(InvalidAiAnalysisException e) {
    return problem(400, "Invalid AI analysis", e.getMessage());
  }

  private static ResponseEntity<ProblemDetail> problem(int status, String title, String detail) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.valueOf(status), detail);
    body.setTitle(title);
    body.setType(
        URI.create("https://arguslog.org/problems/" + title.toLowerCase().replace(' ', '-')));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  static final class BadFilterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BadFilterException(String field, String value, String hint) {
      super("query parameter '" + field + "'='" + value + "' is invalid: " + hint);
    }
  }
}
