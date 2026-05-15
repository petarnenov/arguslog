package org.arguslog.api.slack.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertDestinationWriteRepository;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.application.port.AlertRuleWriteRepository;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.application.port.DsnWriteRepository;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.MembershipWriteRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.email.InviteEmailSender;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.arguslog.api.slack.domain.SlackWorkspace;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dashboard CRUD on Slack workspaces. The test reuses the SlackControllerTest pattern (slack
 * enabled + mock wall) because the @{@code Profile("!test")} guards on real DataSource etc.
 * skip in this profile.
 *
 * <p>Critical assertion across these tests: the response NEVER contains the {@code installToken}
 * field. Leaking the bot token through a list response would be a serious security regression.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "arguslog.slack.enabled=true",
      "arguslog.slack.oauth.client-id=client-abc",
      "arguslog.slack.oauth.client-secret=secret-xyz",
      "arguslog.slack.oauth.state-secret=state-sec",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"
    })
class IntegrationsSlackControllerTest {

  private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final Instant INSTALLED_AT = Instant.parse("2026-05-15T10:00:00Z");

  @Autowired MockMvc mvc;

  @MockitoBean SlackWorkspaceRepository slackWorkspaceRepository;
  @MockitoBean SlackWorkspaceWriteRepository slackWorkspaceWriteRepository;
  @MockitoBean ProjectRepository projectRepository;

  // Other slack ports stubbed so the @Conditional-loaded beans don't autowire DataSource.
  @MockitoBean org.arguslog.api.slack.application.SlackCommandDispatcher slackCommandDispatcher;
  @MockitoBean org.arguslog.api.slack.application.SlackSigningVerifier slackSigningVerifier;
  @MockitoBean org.arguslog.api.slack.application.SlackInstallStateCodec slackInstallStateCodec;
  @MockitoBean org.arguslog.api.slack.application.SlackOAuthService slackOAuthService;

  // Mock wall — mirrors AbstractControllerTest so this standalone context boots without
  // real infra beans.
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean ProjectWriteRepository projectWriteRepository;
  @MockitoBean MembershipRepository membershipRepository;
  @MockitoBean MembershipWriteRepository membershipWriteRepository;
  @MockitoBean InviteEmailSender inviteEmailSender;
  @MockitoBean OrgWriteRepository orgWriteRepository;
  @MockitoBean UserRepository userRepository;
  @MockitoBean AlertDestinationRepository alertDestinationRepository;
  @MockitoBean AlertDestinationWriteRepository alertDestinationWriteRepository;
  @MockitoBean AlertRuleRepository alertRuleRepository;
  @MockitoBean AlertRuleWriteRepository alertRuleWriteRepository;
  @MockitoBean DsnRepository dsnRepository;
  @MockitoBean DsnWriteRepository dsnWriteRepository;
  @MockitoBean PlatformRepository platformRepository;
  @MockitoBean ReleaseRepository releaseRepository;
  @MockitoBean SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean SourceMapArtifactWriteRepository sourceMapArtifactWriteRepository;
  @MockitoBean SourceMapStorage sourceMapStorage;
  @MockitoBean PatRepository patRepository;
  @MockitoBean TokenHasher tokenHasher;
  @MockitoBean TierLookupRepository tierLookupRepository;
  @MockitoBean org.arguslog.api.admin.application.port.AdminQueryPort adminQueryPort;

  @Test
  void listReturnsScrubbedMetadataNeverInstallToken() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L))
        .thenReturn(
            List.of(
                new SlackWorkspace(
                    7L,
                    "T123",
                    "Acme",
                    "xoxb-super-secret-do-not-leak",
                    1L,
                    101L,
                    USER,
                    INSTALLED_AT,
                    null,
                    null,
                    null)));

    mvc.perform(get("/api/v1/orgs/1/integrations/slack/workspaces"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(7))
        .andExpect(jsonPath("$[0].slackTeamId").value("T123"))
        .andExpect(jsonPath("$[0].slackTeamName").value("Acme"))
        .andExpect(jsonPath("$[0].defaultProjectId").value(101))
        .andExpect(jsonPath("$[0].active").value(true))
        // Critical: bot token must NEVER appear in any response.
        .andExpect(jsonPath("$[0].installToken").doesNotExist())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("xoxb-super"))));
  }

  @Test
  void deleteOnOwnedWorkspaceDeactivatesAndReturns204() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L))
        .thenReturn(
            List.of(workspace(7L, /* deactivated */ false)));

    mvc.perform(delete("/api/v1/orgs/1/integrations/slack/workspaces/7"))
        .andExpect(status().isNoContent());

    verify(slackWorkspaceWriteRepository).deactivate(7L);
  }

  @Test
  void deleteOnAlreadyDeactivatedWorkspaceIsIdempotent() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L))
        .thenReturn(List.of(workspace(7L, /* deactivated */ true)));

    mvc.perform(delete("/api/v1/orgs/1/integrations/slack/workspaces/7"))
        .andExpect(status().isNoContent());

    verify(slackWorkspaceWriteRepository, never()).deactivate(anyLong());
  }

  @Test
  void deleteCrossOrgReturns404WithoutLeakingExistence() throws Exception {
    // Org 1 asks to delete workspace 99 — which actually belongs to org 2. listForOrg(1) returns
    // empty, controller throws notFound. We verify no deactivate is called.
    when(slackWorkspaceRepository.listForOrg(1L)).thenReturn(List.of());

    mvc.perform(delete("/api/v1/orgs/1/integrations/slack/workspaces/99"))
        .andExpect(status().isNotFound());

    verify(slackWorkspaceWriteRepository, never()).deactivate(anyLong());
  }

  @Test
  void patchSetsDefaultProjectWhenProjectBelongsToOrg() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L)).thenReturn(List.of(workspace(7L, false)));
    when(projectRepository.findOrgIdForProject(202L)).thenReturn(OptionalLong.of(1L));
    when(slackWorkspaceWriteRepository.setDefaultProject(7L, 202L))
        .thenReturn(
            new SlackWorkspace(7L, "T123", "Acme", "tok", 1L, 202L, USER, INSTALLED_AT, null, null, null));

    mvc.perform(
            patch("/api/v1/orgs/1/integrations/slack/workspaces/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultProjectId\": 202}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultProjectId").value(202))
        .andExpect(jsonPath("$.installToken").doesNotExist());

    verify(slackWorkspaceWriteRepository).setDefaultProject(7L, 202L);
  }

  @Test
  void patchClearsDefaultProjectWhenIdIsNull() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L)).thenReturn(List.of(workspace(7L, false)));
    when(slackWorkspaceWriteRepository.setDefaultProject(7L, null))
        .thenReturn(
            new SlackWorkspace(7L, "T123", "Acme", "tok", 1L, null, USER, INSTALLED_AT, null, null, null));

    mvc.perform(
            patch("/api/v1/orgs/1/integrations/slack/workspaces/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultProjectId\": null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultProjectId").doesNotExist());
  }

  @Test
  void patchWithCrossOrgProjectReturns400() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L)).thenReturn(List.of(workspace(7L, false)));
    // Project belongs to org 2, not org 1.
    when(projectRepository.findOrgIdForProject(303L)).thenReturn(OptionalLong.of(2L));

    mvc.perform(
            patch("/api/v1/orgs/1/integrations/slack/workspaces/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultProjectId\": 303}"))
        .andExpect(status().isBadRequest());

    verify(slackWorkspaceWriteRepository, never()).setDefaultProject(anyLong(), any());
  }

  @Test
  void patchWithUnknownProjectReturns400() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L)).thenReturn(List.of(workspace(7L, false)));
    when(projectRepository.findOrgIdForProject(404L)).thenReturn(OptionalLong.empty());

    mvc.perform(
            patch("/api/v1/orgs/1/integrations/slack/workspaces/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultProjectId\": 404}"))
        .andExpect(status().isBadRequest());

    verify(slackWorkspaceWriteRepository, never()).setDefaultProject(anyLong(), any());
  }

  @Test
  void patchCrossOrgWorkspaceReturns404() throws Exception {
    when(slackWorkspaceRepository.listForOrg(1L)).thenReturn(List.of());

    mvc.perform(
            patch("/api/v1/orgs/1/integrations/slack/workspaces/99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultProjectId\": 1}"))
        .andExpect(status().isNotFound());

    verify(slackWorkspaceWriteRepository, never()).setDefaultProject(anyLong(), any());
  }

  private static SlackWorkspace workspace(long id, boolean deactivated) {
    return workspace(id, deactivated, null, null);
  }

  private static SlackWorkspace workspace(
      long id, boolean deactivated, String webhookUrl, String webhookChannel) {
    return new SlackWorkspace(
        id,
        "T" + id,
        "Acme",
        "xoxb-tok",
        1L,
        101L,
        USER,
        INSTALLED_AT,
        deactivated ? INSTALLED_AT.plusSeconds(60) : null,
        webhookUrl,
        webhookChannel);
  }
}
