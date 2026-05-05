package dev.argus.api.adapter.in.web;

import dev.argus.api.adapter.in.web.dto.EventResponse;
import dev.argus.api.adapter.in.web.dto.IssueResponse;
import dev.argus.api.adapter.in.web.dto.PageResponse;
import dev.argus.api.application.CursorCodec.InvalidCursorException;
import dev.argus.api.application.GetIssueUseCase;
import dev.argus.api.application.ListIssueEventsUseCase;
import dev.argus.api.application.ListIssuesUseCase;
import dev.argus.api.application.ListIssuesUseCase.Query;
import dev.argus.api.domain.Issue;
import dev.argus.api.security.AccessException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

  public IssueController(
      ListIssuesUseCase listIssues, GetIssueUseCase getIssue, ListIssueEventsUseCase listEvents) {
    this.listIssues = listIssues;
    this.getIssue = getIssue;
    this.listEvents = listEvents;
  }

  @GetMapping
  public PageResponse<IssueResponse> list(
      @PathVariable long projectId,
      @RequestParam(value = "status", required = false) String statusParam,
      @RequestParam(value = "level", required = false) String levelParam,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {

    Optional<Issue.Status> status = parseStatus(statusParam);
    Optional<Issue.Level> level = parseLevel(levelParam);
    var page =
        listIssues.list(new Query(projectId, status, level, Optional.ofNullable(cursor), limit));
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

  private static Optional<Issue.Status> parseStatus(String raw) {
    if (raw == null || raw.isBlank()) return Optional.empty();
    try {
      return Optional.of(Issue.Status.fromString(raw));
    } catch (IllegalArgumentException e) {
      throw new BadFilterException("status", raw, "must be one of: unresolved, resolved, ignored");
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

  private static ResponseEntity<ProblemDetail> problem(int status, String title, String detail) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.valueOf(status), detail);
    body.setTitle(title);
    body.setType(URI.create("https://argus.dev/problems/" + title.toLowerCase().replace(' ', '-')));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  static final class BadFilterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BadFilterException(String field, String value, String hint) {
      super("query parameter '" + field + "'='" + value + "' is invalid: " + hint);
    }
  }
}
