package org.arguslog.api.slack.application;

import java.util.Optional;
import org.arguslog.api.application.GetIssueUseCase;
import org.arguslog.api.application.IssueTriageUseCase;
import org.arguslog.api.application.IssuesByReleaseUseCase;
import org.arguslog.api.application.ListIssuesUseCase;
import org.arguslog.api.application.ListIssuesUseCase.Query;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.domain.Org;
import org.arguslog.api.domain.Project;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.security.OrgContext;
import org.arguslog.api.slack.adapter.in.web.dto.SlackCommandPayload;
import org.arguslog.api.slack.adapter.in.web.dto.SlackCommandResponse;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.arguslog.api.slack.domain.SlackWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Parses `/arguslog <subcommand>` payloads from Slack and routes to the existing use cases.
 * No new business logic — this class is purely a translation layer between Slack's text input
 * and the REST API's typed handlers.
 *
 * <p>Subcommands shipped in v1: {@code help}, {@code issues}, {@code issue <id>}, {@code
 * resolve <id>}, {@code release <version>}. {@code ping} is deliberately deferred — it
 * requires synthetic-event building in Java + an HTTP client to ingest, which is more work
 * than this thin slice should carry; operators can use the dashboard Connect wizard's Test
 * ping button instead.
 *
 * <p>OrgContext lifecycle: the controller looks up the SlackWorkspace by team_id (bypasses
 * RLS — see {@link SlackWorkspaceRepository#findActiveByTeamId}), then this dispatcher pins
 * OrgContext to {@code workspace.orgId} before calling any downstream repo. ALWAYS cleared
 * in a finally block so a thrown exception doesn't leak the context to a thread-pooled
 * subsequent request.
 */
// `arguslog.slack.enabled=false` opts the entire Slack stack out — used by every test context
// that doesn't want a DataSource autowire failure. Production / staging leave the property
// unset → default true → all three beans (repo, dispatcher, controller) load.
@Service
@ConditionalOnProperty(name = "arguslog.slack.enabled", havingValue = "true", matchIfMissing = true)
public class SlackCommandDispatcher {

  private static final Logger log = LoggerFactory.getLogger(SlackCommandDispatcher.class);
  private static final int ISSUE_LIST_LIMIT = 10;

  private final SlackWorkspaceRepository workspaces;
  private final SlackWorkspaceWriteRepository workspaceWrites;
  private final OrgWriteRepository orgs;
  private final ProjectWriteRepository projects;
  private final ListIssuesUseCase listIssues;
  private final GetIssueUseCase getIssue;
  private final IssueTriageUseCase triage;
  private final ReleaseRepository releases;
  private final IssuesByReleaseUseCase issuesByRelease;
  private final SlackBlockBuilder blocks;

  public SlackCommandDispatcher(
      SlackWorkspaceRepository workspaces,
      SlackWorkspaceWriteRepository workspaceWrites,
      OrgWriteRepository orgs,
      ProjectWriteRepository projects,
      ListIssuesUseCase listIssues,
      GetIssueUseCase getIssue,
      IssueTriageUseCase triage,
      ReleaseRepository releases,
      IssuesByReleaseUseCase issuesByRelease,
      @Value("${arguslog.dashboard.base-url:http://localhost:5173}") String dashboardBaseUrl) {
    this.workspaces = workspaces;
    this.workspaceWrites = workspaceWrites;
    this.orgs = orgs;
    this.projects = projects;
    this.listIssues = listIssues;
    this.getIssue = getIssue;
    this.triage = triage;
    this.releases = releases;
    this.issuesByRelease = issuesByRelease;
    this.blocks = new SlackBlockBuilder(dashboardBaseUrl);
  }

  public SlackCommandResponse dispatch(SlackCommandPayload payload) {
    Optional<SlackWorkspace> workspaceOpt = workspaces.findActiveByTeamId(payload.teamId());
    if (workspaceOpt.isEmpty()) {
      // Slack will retry on 200; replying with an ephemeral error is cleaner than a 4xx.
      return SlackCommandResponse.ephemeralText(
          "This Slack workspace isn't connected to an Arguslog org. Install the app from the"
              + " Arguslog dashboard → Integrations → Slack.");
    }
    SlackWorkspace workspace = workspaceOpt.get();

    Org org = orgs.findById(workspace.orgId()).orElse(null);
    if (org == null) {
      return SlackCommandResponse.ephemeralText(
          "Connected org no longer exists. Re-install the Slack app from the dashboard.");
    }

    String[] parts = splitText(payload.text());
    String subcommand = parts.length == 0 ? "help" : parts[0].toLowerCase();

    OrgContext.set(workspace.orgId());
    try {
      return switch (subcommand) {
        case "help", "" -> SlackCommandResponse.ephemeral("Arguslog help", blocks.help());
        case "issues" -> handleIssues(workspace, org);
        case "issue" -> handleIssueDetail(workspace, org, parts);
        case "resolve" -> handleResolve(workspace, org, parts);
        case "ignore" -> handleIgnore(workspace, org, parts);
        case "reopen" -> handleReopen(workspace, org, parts);
        case "release" -> handleRelease(workspace, org, parts);
        case "set-project" -> handleSetProject(workspace, org, parts);
        default ->
            SlackCommandResponse.ephemeralText(
                "Unknown subcommand `" + subcommand + "`. Try `/arguslog help`.");
      };
    } catch (RuntimeException e) {
      log.warn("slack command failed (subcommand={}, team={}): {}",
          subcommand, payload.teamId(), e.getMessage());
      return SlackCommandResponse.ephemeralText("⚠️ Internal error — check the api logs.");
    } finally {
      OrgContext.clear();
    }
  }

  private SlackCommandResponse handleIssues(SlackWorkspace workspace, Org org) {
    Long projectId = workspace.defaultProjectId();
    if (projectId == null) {
      return SlackCommandResponse.ephemeralText(
          "No default project set for this workspace. Open the dashboard and pick one in"
              + " Integrations → Slack.");
    }
    var page =
        listIssues.list(
            new Query(
                projectId,
                Optional.of(Issue.Status.UNRESOLVED),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ISSUE_LIST_LIMIT));
    return SlackCommandResponse.ephemeral(
        "Issues in " + org.slug(),
        blocks.issuesList(org.slug(), projectId, page.issues()));
  }

  private SlackCommandResponse handleIssueDetail(
      SlackWorkspace workspace, Org org, String[] parts) {
    Long issueId = parsePositive(parts, 1);
    if (issueId == null) return SlackCommandResponse.ephemeralText("Usage: `/arguslog issue <id>`");
    Long projectId = workspace.defaultProjectId();
    if (projectId == null) {
      return SlackCommandResponse.ephemeralText("No default project set for this workspace.");
    }
    Optional<Issue> issue = getIssue.get(projectId, issueId);
    if (issue.isEmpty()) {
      return SlackCommandResponse.ephemeralText("Issue #" + issueId + " not found in default project.");
    }
    return SlackCommandResponse.ephemeral(
        "Issue #" + issueId, blocks.issueDetail(org.slug(), issue.get()));
  }

  private SlackCommandResponse handleResolve(
      SlackWorkspace workspace, Org org, String[] parts) {
    Long issueId = parsePositive(parts, 1);
    if (issueId == null) return SlackCommandResponse.ephemeralText("Usage: `/arguslog resolve <id>`");
    Long projectId = workspace.defaultProjectId();
    if (projectId == null) {
      return SlackCommandResponse.ephemeralText("No default project set for this workspace.");
    }
    Optional<Issue> updated =
        triage.updateStatus(workspace.orgId(), projectId, issueId, Issue.Status.RESOLVED);
    if (updated.isEmpty()) {
      return SlackCommandResponse.ephemeralText("Issue #" + issueId + " not found.");
    }
    // Mutating commands broadcast to channel so the rest of the team sees the audit trail.
    return SlackCommandResponse.inChannel(
        "Resolved", blocks.resolvedConfirmation(org.slug(), updated.get()));
  }

  private SlackCommandResponse handleIgnore(
      SlackWorkspace workspace, Org org, String[] parts) {
    Long issueId = parsePositive(parts, 1);
    if (issueId == null) return SlackCommandResponse.ephemeralText("Usage: `/arguslog ignore <id>`");
    Long projectId = workspace.defaultProjectId();
    if (projectId == null) {
      return SlackCommandResponse.ephemeralText("No default project set for this workspace.");
    }
    Optional<Issue> updated =
        triage.updateStatus(workspace.orgId(), projectId, issueId, Issue.Status.IGNORED);
    if (updated.isEmpty()) {
      return SlackCommandResponse.ephemeralText("Issue #" + issueId + " not found.");
    }
    return SlackCommandResponse.inChannel(
        "Ignored", blocks.ignoredConfirmation(org.slug(), updated.get()));
  }

  private SlackCommandResponse handleReopen(
      SlackWorkspace workspace, Org org, String[] parts) {
    Long issueId = parsePositive(parts, 1);
    if (issueId == null) return SlackCommandResponse.ephemeralText("Usage: `/arguslog reopen <id>`");
    Long projectId = workspace.defaultProjectId();
    if (projectId == null) {
      return SlackCommandResponse.ephemeralText("No default project set for this workspace.");
    }
    Optional<Issue> updated =
        triage.updateStatus(workspace.orgId(), projectId, issueId, Issue.Status.UNRESOLVED);
    if (updated.isEmpty()) {
      return SlackCommandResponse.ephemeralText("Issue #" + issueId + " not found.");
    }
    return SlackCommandResponse.inChannel(
        "Reopened", blocks.reopenedConfirmation(org.slug(), updated.get()));
  }

  private SlackCommandResponse handleRelease(
      SlackWorkspace workspace, Org org, String[] parts) {
    if (parts.length < 2) {
      return SlackCommandResponse.ephemeralText("Usage: `/arguslog release <version>`");
    }
    String version = parts[1];
    Long projectId = workspace.defaultProjectId();
    if (projectId == null) {
      return SlackCommandResponse.ephemeralText("No default project set for this workspace.");
    }
    Optional<Release> release = releases.findByVersion(projectId, version);
    if (release.isEmpty()) {
      return SlackCommandResponse.ephemeralText(
          "Release `" + version + "` not found in default project.");
    }
    var issuesList = issuesByRelease.list(projectId, release.get().id());
    return SlackCommandResponse.ephemeral(
        "Release " + version,
        blocks.releaseIssues(org.slug(), projectId, version, issuesList));
  }

  private SlackCommandResponse handleSetProject(
      SlackWorkspace workspace, Org org, String[] parts) {
    if (parts.length < 2 || parts[1].isBlank()) {
      return SlackCommandResponse.ephemeralText(
          "Usage: `/arguslog set-project <slug>` (find the slug in Dashboard → Projects).");
    }
    String slug = parts[1];
    Optional<Project> hit =
        projects.listForOrg(org.id()).stream().filter(p -> slug.equals(p.slug())).findFirst();
    if (hit.isEmpty()) {
      return SlackCommandResponse.ephemeralText(
          "Project `" + slug + "` not found in " + org.slug() + ".");
    }
    workspaceWrites.setDefaultProject(workspace.id(), hit.get().id());
    // In-channel so the rest of the team sees the change without having to ask.
    return SlackCommandResponse.inChannel(
        "Default project updated",
        blocks.setProjectConfirmation(org.slug(), slug, hit.get().name()));
  }

  private static String[] splitText(String text) {
    if (text == null || text.isBlank()) return new String[0];
    return text.trim().split("\\s+");
  }

  /** Parses parts[index] as a positive long; returns null on missing / negative / non-numeric. */
  private static Long parsePositive(String[] parts, int index) {
    if (parts.length <= index) return null;
    try {
      long n = Long.parseLong(parts[index]);
      return n > 0 ? n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
