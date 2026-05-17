package org.arguslog.api.adapter.in.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.arguslog.api.adapter.in.web.dto.GitBranchResponse;
import org.arguslog.api.adapter.in.web.dto.ProjectCreateResponse;
import org.arguslog.api.adapter.in.web.dto.ProjectRenameRequest;
import org.arguslog.api.adapter.in.web.dto.ProjectRequest;
import org.arguslog.api.adapter.in.web.dto.ProjectResponse;
import org.arguslog.api.adapter.out.git.GitPublicClient;
import org.arguslog.api.adapter.out.git.GitPublicClient.BranchListResult;
import org.arguslog.api.application.DsnUseCase;
import org.arguslog.api.application.ProjectUseCase;
import org.arguslog.api.application.ProjectUseCase.DuplicateProjectException;
import org.arguslog.api.application.ProjectUseCase.InvalidProjectException;
import org.arguslog.api.application.ProjectUseCase.ProjectAccessDeniedException;
import org.arguslog.api.auth.PatScopeGuard;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.domain.Dsn;
import org.arguslog.api.domain.GitProvider;
import org.arguslog.api.domain.Project;
import org.arguslog.api.security.AccessException;
import org.arguslog.api.security.AuthActor;
import org.springframework.beans.factory.annotation.Value;
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

@RestController
@RequestMapping(
    value = "/api/v1/orgs/{orgId}/projects",
    produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectController {

  private final ProjectUseCase useCase;
  private final DsnUseCase dsnUseCase;
  private final GitPublicClient git;
  private final String ingestHost;

  public ProjectController(
      ProjectUseCase useCase,
      DsnUseCase dsnUseCase,
      GitPublicClient git,
      @Value("${arguslog.ingest.public-host:http://localhost:8080}") String ingestHost) {
    this.useCase = useCase;
    this.dsnUseCase = dsnUseCase;
    this.git = git;
    this.ingestHost = ingestHost;
  }

  @GetMapping
  public List<ProjectResponse> list(@PathVariable long orgId) {
    java.util.List<Project> projects = useCase.list(orgId);
    java.util.Map<Long, org.arguslog.api.application.dto.ProjectStats> stats =
        useCase.statsForOrg(orgId);
    return projects.stream().map(p -> ProjectResponse.from(p, stats.get(p.id()))).toList();
  }

  /**
   * Creates the project AND mints its first DSN in one round-trip. Returning the DSN inline (GH
   * #26) means the web onboarding flow can pop the "copy your key" modal immediately without a
   * chained POST that used to race with the browser tab closing mid-flow — leaving an orphan
   * project that ingested nothing.
   *
   * <p>The full DSN string is visible exactly once here (GitHub PAT pattern); follow-up listings
   * return {@link org.arguslog.api.adapter.in.web.dto.DsnSummaryResponse} which omits it.
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProjectCreateResponse> create(
      @PathVariable long orgId, @RequestBody ProjectRequest body) {
    PatScopeGuard.require(PatScope.PROJECTS_WRITE);
    GitProvider provider = parseProvider(body.gitProvider());
    Project createdProject =
        useCase.create(orgId, body.name(), body.platform(), provider, body.gitRepo());
    Dsn createdDsn = dsnUseCase.create(createdProject.id());
    return ResponseEntity.created(URI.create(String.valueOf(createdProject.id())))
        .body(ProjectCreateResponse.from(createdProject, createdDsn, ingestHost));
  }

  @GetMapping("/{projectId}")
  public ProjectResponse get(@PathVariable long orgId, @PathVariable long projectId) {
    return useCase
        .get(orgId, projectId)
        .map(ProjectResponse::from)
        .orElseThrow(() -> AccessException.notFound(projectId));
  }

  /**
   * Partial update: changes the display name and/or the Git repo link. Each field is optional —
   * a {@code null} JSON value means "leave unchanged"; sending {@code gitProvider} and
   * {@code gitRepo} both as empty strings clears the link. Slug is preserved. Owner/admin only.
   */
  @PatchMapping(value = "/{projectId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ProjectResponse update(
      @PathVariable long orgId,
      @PathVariable long projectId,
      @RequestBody ProjectRenameRequest body) {
    PatScopeGuard.require(PatScope.PROJECTS_WRITE);
    UUID actorId = AuthActor.currentUserId();
    Project current = null;
    if (body.name() != null) {
      current =
          useCase
              .rename(actorId, orgId, projectId, body.name())
              .orElseThrow(() -> AccessException.notFound(projectId));
    }
    // Either field present means "the caller wants to touch the Git link" — empty strings on
    // both clear it; valid pair sets it. Mixing one set with the other null is rejected in the
    // service layer with a 400.
    if (body.gitProvider() != null || body.gitRepo() != null) {
      GitProvider provider = parseProvider(body.gitProvider());
      current =
          useCase
              .updateGitRepo(actorId, orgId, projectId, provider, body.gitRepo())
              .orElseThrow(() -> AccessException.notFound(projectId));
    }
    if (current == null) {
      // No-op PATCH (all fields null): return the current state rather than 400 so clients can
      // use this endpoint as a fetch-and-touch without special-casing empty bodies.
      current = useCase.get(orgId, projectId).orElseThrow(() -> AccessException.notFound(projectId));
    }
    return ProjectResponse.from(current);
  }

  /**
   * Proxies the configured Git provider's public branches API for this project. Used by the
   * "Create release" modal to populate a branch dropdown and auto-fill the head SHA on select.
   * Returns 422 if the project has no Git repo configured; 404 if the provider reports the repo
   * as missing (private or typo); 429 if we (or the user behind the same IP) blew the
   * unauthenticated rate budget; 502 for any other upstream / transport failure.
   *
   * <p>No auth is sent — public repos only. Self-hosted / private-repo support is a separate
   * feature; the wire shape here stays the same so the UI can grow into it without changes.
   */
  @GetMapping("/{projectId}/git/branches")
  public List<GitBranchResponse> listGitBranches(
      @PathVariable long orgId, @PathVariable long projectId) {
    PatScopeGuard.require(PatScope.PROJECTS_READ);
    Project project =
        useCase.get(orgId, projectId).orElseThrow(() -> AccessException.notFound(projectId));
    GitProvider provider = project.gitProvider();
    String repo = project.gitRepo();
    if (provider == null || repo == null || repo.isBlank()) {
      throw new GitRepoMissingException(
          "This project has no Git repository configured. Set one in Project settings.");
    }
    BranchListResult result = git.listBranches(provider, repo);
    if (result instanceof BranchListResult.Ok ok) {
      return ok.branches().stream()
          .map(b -> new GitBranchResponse(b.name(), b.sha()))
          .toList();
    }
    if (result instanceof BranchListResult.NotFound) {
      throw new GitRepoNotFoundException(
          provider.dbValue()
              + " couldn't find repository \""
              + repo
              + "\". Public repos only — private repos are not yet supported.");
    }
    if (result instanceof BranchListResult.RateLimited rl) {
      throw new GitRateLimitedException(provider, rl.resetAt());
    }
    if (result instanceof BranchListResult.TransportError te) {
      throw new GitUpstreamException(provider, te.message());
    }
    // Defensive: sealed switch covers all cases, but keep compile happy.
    throw new GitUpstreamException(provider, "unknown_result");
  }

  /** Thrown when the caller hits the branches endpoint but no Git repo is configured. */
  static final class GitRepoMissingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GitRepoMissingException(String message) {
      super(message);
    }
  }

  /** Thrown when the provider returns 404 — repo is private or doesn't exist. */
  static final class GitRepoNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GitRepoNotFoundException(String message) {
      super(message);
    }
  }

  /** Thrown when the provider rate-limits the unauthenticated request. */
  static final class GitRateLimitedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final GitProvider provider;
    private final Instant resetAt;

    GitRateLimitedException(GitProvider provider, Instant resetAt) {
      super(provider.dbValue() + " rate limit exhausted; try again at " + resetAt);
      this.provider = provider;
      this.resetAt = resetAt;
    }

    GitProvider provider() {
      return provider;
    }

    Instant resetAt() {
      return resetAt;
    }
  }

  /** Catch-all for upstream transport/HTTP failures. */
  static final class GitUpstreamException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GitUpstreamException(GitProvider provider, String detail) {
      super(provider.dbValue() + " upstream error: " + detail);
    }
  }

  /**
   * Soft-archives a project. DELETE semantics on the wire (idempotent from the client's view) but
   * server-side it just flips {@code archived_at}, so issues/events stay queryable for incident
   * review. Owner/admin only.
   */
  @DeleteMapping("/{projectId}")
  public ResponseEntity<Void> archive(@PathVariable long orgId, @PathVariable long projectId) {
    PatScopeGuard.require(PatScope.PROJECTS_WRITE);
    UUID actorId = AuthActor.currentUserId();
    if (!useCase.archive(actorId, orgId, projectId)) {
      throw AccessException.notFound(projectId);
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Parses the wire-level {@code gitProvider} string. Empty string is treated as {@code null}
   * (no provider — caller is clearing). Any other unknown value is rejected with a 400.
   */
  private static GitProvider parseProvider(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return GitProvider.fromDbValue(raw.trim())
        .orElseThrow(
            () ->
                new InvalidProjectException(
                    "gitProvider must be one of: github, gitlab — got: " + raw));
  }

  @ExceptionHandler(InvalidProjectException.class)
  ResponseEntity<ProblemDetail> handleInvalid(InvalidProjectException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    body.setTitle("Invalid project");
    body.setType(URI.create("https://arguslog.org/problems/invalid-project"));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(DuplicateProjectException.class)
  ResponseEntity<ProblemDetail> handleDuplicate(DuplicateProjectException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setTitle("Duplicate project");
    body.setType(URI.create("https://arguslog.org/problems/duplicate-project"));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(ProjectAccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleForbidden(ProjectAccessDeniedException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    body.setTitle("Forbidden");
    body.setType(URI.create("https://arguslog.org/problems/project-access-denied"));
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(org.arguslog.api.application.ProjectCapExceededException.class)
  ResponseEntity<ProblemDetail> handleCapExceeded(
      org.arguslog.api.application.ProjectCapExceededException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    body.setTitle("Project cap exceeded");
    body.setType(URI.create("https://arguslog.org/problems/project-cap-exceeded"));
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(GitRepoMissingException.class)
  ResponseEntity<ProblemDetail> handleGitRepoMissing(GitRepoMissingException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    body.setTitle("Git repository not configured");
    body.setType(URI.create("https://arguslog.org/problems/git-repo-missing"));
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(GitRepoNotFoundException.class)
  ResponseEntity<ProblemDetail> handleGitRepoNotFound(GitRepoNotFoundException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    body.setTitle("Git repository not found");
    body.setType(URI.create("https://arguslog.org/problems/git-repo-not-found"));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(GitRateLimitedException.class)
  ResponseEntity<ProblemDetail> handleGitRateLimited(GitRateLimitedException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    body.setTitle("Git provider rate limited");
    body.setType(URI.create("https://arguslog.org/problems/git-rate-limited"));
    body.setProperty("provider", e.provider().dbValue());
    body.setProperty("resetAt", e.resetAt().toString());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(GitUpstreamException.class)
  ResponseEntity<ProblemDetail> handleGitUpstream(GitUpstreamException e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    body.setTitle("Git upstream error");
    body.setType(URI.create("https://arguslog.org/problems/git-upstream"));
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
