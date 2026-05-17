package org.arguslog.api.slack.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.application.GetIssueUseCase;
import org.arguslog.api.application.IssueTriageUseCase;
import org.arguslog.api.application.IssuesByReleaseUseCase;
import org.arguslog.api.application.ListIssuesUseCase;
import org.arguslog.api.application.ListIssuesUseCase.Page;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.domain.Org;
import org.arguslog.api.domain.Project;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.slack.adapter.in.web.dto.SlackCommandPayload;
import org.arguslog.api.slack.adapter.in.web.dto.SlackCommandResponse;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.arguslog.api.slack.domain.SlackWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Each subcommand routes to the right downstream use case and produces a response shape that Slack
 * will render. The dispatcher itself is a pure routing layer — the underlying use cases have their
 * own unit / integration tests, so we mock them out here.
 */
@ExtendWith(MockitoExtension.class)
class SlackCommandDispatcherTest {

  @Mock SlackWorkspaceRepository workspaces;
  @Mock SlackWorkspaceWriteRepository workspaceWrites;
  @Mock OrgWriteRepository orgs;
  @Mock ProjectWriteRepository projects;
  @Mock ListIssuesUseCase listIssues;
  @Mock GetIssueUseCase getIssue;
  @Mock IssueTriageUseCase triage;
  @Mock ReleaseRepository releases;
  @Mock IssuesByReleaseUseCase issuesByRelease;

  SlackCommandDispatcher dispatcher;

  private static final SlackWorkspace WORKSPACE =
      new SlackWorkspace(
          7L,
          "T123",
          "Acme Workspace",
          "xoxb-fake",
          1L,
          101L,
          null,
          Instant.parse("2026-05-01T00:00:00Z"),
          null,
          null,
          null);
  private static final Org ORG =
      new Org(1L, "acme", "Acme", "regular", Instant.parse("2026-05-01T00:00:00Z"));

  @BeforeEach
  void setUp() {
    dispatcher =
        new SlackCommandDispatcher(
            workspaces,
            workspaceWrites,
            orgs,
            projects,
            listIssues,
            getIssue,
            triage,
            releases,
            issuesByRelease,
            "https://app.arguslog.org");
  }

  @Test
  void unknownTeamSurfacesInstallPrompt() {
    when(workspaces.findActiveByTeamId("T999")).thenReturn(Optional.empty());
    SlackCommandResponse r = dispatcher.dispatch(payload("T999", "help"));
    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.text()).contains("isn't connected");
  }

  @Test
  void helpReturnsBlockKit() {
    primeWorkspace();
    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "help"));
    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.blocks()).isNotEmpty();
    verify(listIssues, never()).list(any());
  }

  @Test
  void emptyTextDefaultsToHelp() {
    primeWorkspace();
    SlackCommandResponse r = dispatcher.dispatch(payload("T123", ""));
    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.blocks()).isNotEmpty();
  }

  @Test
  void issuesListRoutesToListIssuesWithUnresolvedFilter() {
    primeWorkspace();
    Issue sample =
        new Issue(
            42L,
            101L,
            "fp",
            Issue.Status.UNRESOLVED,
            Issue.Level.ERROR,
            "boom",
            "src/app.ts:1",
            Instant.now(),
            Instant.now(),
            3L,
            null);
    when(listIssues.list(any())).thenReturn(new Page(List.of(sample), Optional.<String>empty()));

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "issues"));
    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.blocks()).isNotEmpty();
    verify(listIssues)
        .list(
            org.mockito.ArgumentMatchers.argThat(
                q ->
                    q.projectId() == 101L
                        && q.status().equals(Optional.of(Issue.Status.UNRESOLVED))
                        && q.limit() == 10));
  }

  @Test
  void issueDetailRoutesToGetIssue() {
    primeWorkspace();
    Issue sample =
        new Issue(
            42L,
            101L,
            "fp",
            Issue.Status.UNRESOLVED,
            Issue.Level.ERROR,
            "boom",
            null,
            Instant.parse("2026-05-01T00:00:00Z"),
            Instant.parse("2026-05-02T00:00:00Z"),
            3L,
            null);
    when(getIssue.get(101L, 42L)).thenReturn(Optional.of(sample));

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "issue 42"));
    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.blocks()).isNotEmpty();
    verify(getIssue).get(101L, 42L);
  }

  @Test
  void issueDetailMissingReturnsFriendlyError() {
    primeWorkspace();
    when(getIssue.get(101L, 9999L)).thenReturn(Optional.empty());
    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "issue 9999"));
    assertThat(r.text()).contains("not found");
  }

  @Test
  void resolveRoutesToTriageAndBroadcastsInChannel() {
    primeWorkspace();
    Issue resolved =
        new Issue(
            42L,
            101L,
            "fp",
            Issue.Status.RESOLVED,
            Issue.Level.ERROR,
            "boom",
            null,
            Instant.now(),
            Instant.now(),
            3L,
            null);
    when(triage.updateStatus(1L, 101L, 42L, Issue.Status.RESOLVED))
        .thenReturn(Optional.of(resolved));

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "resolve 42"));
    assertThat(r.response_type()).isEqualTo("in_channel"); // Mutations broadcast.
    verify(triage).updateStatus(1L, 101L, 42L, Issue.Status.RESOLVED);
  }

  @Test
  void ignoreRoutesToTriageWithIgnoredStatusAndBroadcasts() {
    primeWorkspace();
    Issue ignored =
        new Issue(
            42L,
            101L,
            "fp",
            Issue.Status.IGNORED,
            Issue.Level.ERROR,
            "boom",
            null,
            Instant.now(),
            Instant.now(),
            3L,
            null);
    when(triage.updateStatus(1L, 101L, 42L, Issue.Status.IGNORED)).thenReturn(Optional.of(ignored));

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "ignore 42"));
    assertThat(r.response_type()).isEqualTo("in_channel");
    verify(triage).updateStatus(1L, 101L, 42L, Issue.Status.IGNORED);
  }

  @Test
  void reopenRoutesToTriageWithUnresolvedStatusAndBroadcasts() {
    primeWorkspace();
    Issue reopened =
        new Issue(
            42L,
            101L,
            "fp",
            Issue.Status.UNRESOLVED,
            Issue.Level.ERROR,
            "boom",
            null,
            Instant.now(),
            Instant.now(),
            3L,
            null);
    when(triage.updateStatus(1L, 101L, 42L, Issue.Status.UNRESOLVED))
        .thenReturn(Optional.of(reopened));

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "reopen 42"));
    assertThat(r.response_type()).isEqualTo("in_channel");
    verify(triage).updateStatus(1L, 101L, 42L, Issue.Status.UNRESOLVED);
  }

  @Test
  void releaseRoutesToFindByVersionThenIssuesByRelease() {
    primeWorkspace();
    Release release = new Release(9L, 101L, "v1.2.3", Instant.now());
    when(releases.findByVersion(101L, "v1.2.3")).thenReturn(Optional.of(release));
    when(issuesByRelease.list(101L, 9L)).thenReturn(List.of());

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "release v1.2.3"));
    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.blocks()).isNotEmpty();
    verify(issuesByRelease).list(101L, 9L);
  }

  @Test
  void releaseUnknownVersionReturnsFriendlyError() {
    primeWorkspace();
    when(releases.findByVersion(eq(101L), any())).thenReturn(Optional.empty());
    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "release nope"));
    assertThat(r.text()).contains("not found");
    verify(issuesByRelease, never()).list(any(Long.class), any(Long.class));
  }

  @Test
  void setProjectSwitchesDefaultProjectAndBroadcastsInChannel() {
    primeWorkspace();
    Project apiProject = new Project(202L, 1L, "api", "Api", "java", Instant.now(), null, null);
    when(projects.listForOrg(1L)).thenReturn(List.of(apiProject));

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "set-project api"));

    // Mutations broadcast to the channel — same posture as resolve.
    assertThat(r.response_type()).isEqualTo("in_channel");
    verify(workspaceWrites).setDefaultProject(7L, 202L);
  }

  @Test
  void setProjectWithUnknownSlugReturnsFriendlyError() {
    primeWorkspace();
    when(projects.listForOrg(1L)).thenReturn(List.of());

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "set-project nope"));

    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.text()).contains("not found");
    verify(workspaceWrites, never()).setDefaultProject(anyLong(), any());
  }

  @Test
  void setProjectWithMissingSlugShowsUsage() {
    primeWorkspace();

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "set-project"));

    assertThat(r.response_type()).isEqualTo("ephemeral");
    assertThat(r.text()).contains("Usage:");
    verify(projects, never()).listForOrg(anyLong());
  }

  @Test
  void unknownSubcommandSurfacesHelpHint() {
    primeWorkspace();
    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "frobnicate 7"));
    assertThat(r.text()).contains("Unknown subcommand");
  }

  @Test
  void noDefaultProjectShortCircuitsBeforeReadingIssues() {
    when(workspaces.findActiveByTeamId("T123"))
        .thenReturn(
            Optional.of(
                new SlackWorkspace(
                    7L, "T123", "Acme", "xoxb", 1L, null, null, Instant.now(), null, null, null)));
    when(orgs.findById(1L)).thenReturn(Optional.of(ORG));

    SlackCommandResponse r = dispatcher.dispatch(payload("T123", "issues"));
    assertThat(r.text()).contains("No default project");
    verify(listIssues, never()).list(any());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void primeWorkspace() {
    when(workspaces.findActiveByTeamId("T123")).thenReturn(Optional.of(WORKSPACE));
    when(orgs.findById(1L)).thenReturn(Optional.of(ORG));
  }

  private static SlackCommandPayload payload(String teamId, String text) {
    return new SlackCommandPayload(teamId, "acme", "C9", "U42", "/arguslog", text, "");
  }
}
