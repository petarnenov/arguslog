package org.arguslog.api.slack.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.arguslog.api.slack.application.SlackInstallStateCodec;
import org.arguslog.api.slack.application.SlackOAuthService;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Standalone @SpringBootTest in the same mold as {@link SlackControllerTest} — turns Slack on
 * via @TestPropertySource, then @MockitoBean replaces the collaborators so the install
 * controller can exercise its routing logic without any real Slack/HTTP dependency.
 *
 * <p>OAuth state codec is mocked (not the real one) so a single string token round-trips
 * deterministically; the codec's own crypto is covered in {@link
 * org.arguslog.api.slack.application.SlackInstallStateCodecTest}.
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
      "arguslog.slack.oauth.redirect-uri=http://localhost:8081/api/v1/slack/oauth/callback",
      "arguslog.slack.oauth.dashboard-base-url=http://localhost:5173",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"
    })
class SlackInstallControllerTest {

  private static final String USER_UUID = "11111111-2222-3333-4444-555555555555";
  private static final UUID USER = UUID.fromString(USER_UUID);

  @Autowired MockMvc mvc;

  @MockitoBean SlackInstallStateCodec slackInstallStateCodec;
  @MockitoBean SlackOAuthService slackOAuthService;
  @MockitoBean SlackWorkspaceWriteRepository slackWorkspaceWriteRepository;

  // Slack ports stubbed so JdbcSlackWorkspaceRepository's @Component skips DataSource autowire.
  @MockitoBean SlackWorkspaceRepository slackWorkspaceRepository;
  // SlackCommandDispatcher pulls a long graph of repos — mock it whole so we don't need to
  // mirror the full controller bean wall here.
  @MockitoBean org.arguslog.api.slack.application.SlackCommandDispatcher slackCommandDispatcher;
  @MockitoBean org.arguslog.api.slack.application.SlackSigningVerifier slackSigningVerifier;

  // Mirror AbstractControllerTest's mock wall so the context boots without DataSource/Flyway.
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean ProjectRepository projectRepository;
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
  @WithMockUser(username = USER_UUID)
  void installRedirectsToSlackAuthorizeWithFreshState() throws Exception {
    when(slackInstallStateCodec.encode(42L, USER)).thenReturn("opaque-state-token");
    when(slackOAuthService.buildAuthorizeUrl(
            "opaque-state-token", "http://localhost:8081/api/v1/slack/oauth/callback"))
        .thenReturn("https://slack.com/oauth/v2/authorize?state=opaque-state-token");

    mvc.perform(get("/api/v1/orgs/42/integrations/slack/oauth/install"))
        .andExpect(status().isFound())
        .andExpect(
            header().string(
                "Location", "https://slack.com/oauth/v2/authorize?state=opaque-state-token"));
  }

  @Test
  void callbackWithGoodStateAndCodeUpsertsAndRedirectsToDashboard() throws Exception {
    when(slackInstallStateCodec.decode("good-state"))
        .thenReturn(SlackInstallStateCodec.Result.ok(42L, USER));
    when(slackOAuthService.exchangeCode(
            eq("the-code"), eq("http://localhost:8081/api/v1/slack/oauth/callback")))
        .thenReturn(new SlackOAuthService.Result.Success("T123", "Acme", "xoxb-x", "U42"));

    mvc.perform(
            get("/api/v1/slack/oauth/callback")
                .param("code", "the-code")
                .param("state", "good-state"))
        .andExpect(status().isFound())
        .andExpect(
            header().string(
                "Location",
                "http://localhost:5173/settings/integrations/slack?installed=Acme"));

    verify(slackWorkspaceWriteRepository)
        .upsert(eq("T123"), eq("Acme"), eq("xoxb-x"), eq(42L), eq(null), eq(USER));
  }

  @Test
  void callbackWithBlankTeamNameFallsBackToTeamIdForDisplay() throws Exception {
    when(slackInstallStateCodec.decode("good-state"))
        .thenReturn(SlackInstallStateCodec.Result.ok(42L, USER));
    when(slackOAuthService.exchangeCode(any(), any()))
        .thenReturn(new SlackOAuthService.Result.Success("T999", "", "xoxb-y", "U42"));

    mvc.perform(
            get("/api/v1/slack/oauth/callback")
                .param("code", "c")
                .param("state", "good-state"))
        .andExpect(status().isFound());

    verify(slackWorkspaceWriteRepository)
        .upsert(eq("T999"), eq("T999"), eq("xoxb-y"), eq(42L), eq(null), eq(USER));
  }

  @Test
  void callbackWithBadStateReturns401WithoutHittingSlack() throws Exception {
    when(slackInstallStateCodec.decode("bad-state"))
        .thenReturn(SlackInstallStateCodec.Result.invalid("expired"));

    mvc.perform(
            get("/api/v1/slack/oauth/callback").param("code", "c").param("state", "bad-state"))
        .andExpect(status().isUnauthorized());

    verify(slackOAuthService, never()).exchangeCode(any(), any());
    verify(slackWorkspaceWriteRepository, never())
        .upsert(any(), any(), any(), anyLong(), any(), any());
  }

  @Test
  void callbackWithSlackErrorParamRedirectsToDashboardWithErrorFlag() throws Exception {
    mvc.perform(get("/api/v1/slack/oauth/callback").param("error", "access_denied"))
        .andExpect(status().isFound())
        .andExpect(
            header().string(
                "Location",
                "http://localhost:5173/settings/integrations/slack?error=access_denied"));

    verify(slackInstallStateCodec, never()).decode(any());
  }

  @Test
  void callbackMissingCodeIs400() throws Exception {
    mvc.perform(get("/api/v1/slack/oauth/callback").param("state", "x"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void callbackWithFailedExchangeRedirectsWithError() throws Exception {
    when(slackInstallStateCodec.decode("good-state"))
        .thenReturn(SlackInstallStateCodec.Result.ok(42L, USER));
    when(slackOAuthService.exchangeCode(any(), any()))
        .thenReturn(new SlackOAuthService.Result.Failure("invalid_code"));

    mvc.perform(
            get("/api/v1/slack/oauth/callback")
                .param("code", "c")
                .param("state", "good-state"))
        .andExpect(status().isFound())
        .andExpect(
            header().string(
                "Location",
                "http://localhost:5173/settings/integrations/slack?error=token_exchange_invalid_code"));

    verify(slackWorkspaceWriteRepository, never())
        .upsert(any(), any(), any(), anyLong(), any(), any());
  }
}
