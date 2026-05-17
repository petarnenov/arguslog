package org.arguslog.api.slack.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.arguslog.api.slack.adapter.in.web.dto.SlackCommandResponse;
import org.arguslog.api.slack.application.SlackCommandDispatcher;
import org.arguslog.api.slack.application.SlackSigningVerifier;
import org.arguslog.api.slack.application.port.SlackWorkspaceRepository;
import org.arguslog.api.slack.application.port.SlackWorkspaceWriteRepository;
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
 * Standalone @SpringBootTest — does NOT extend AbstractControllerTest because the Slack subsystem
 * is guarded by {@code @Profile("!test")} and the shared base activates {@code test}. Using a
 * different profile name ({@code slack-test}) lets the SlackController + dispatcher load,
 * then @MockitoBean replaces the dispatcher / verifier / repos so no real DataSource is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      // application-test.yml turns Slack off by default; this test reverses that so the
      // controller bean loads (with mocked collaborators below).
      "arguslog.slack.enabled=true",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"
    })
class SlackControllerTest {

  @Autowired MockMvc mvc;

  // Slack collaborators we actually exercise.
  @MockitoBean SlackSigningVerifier slackSigningVerifier;
  @MockitoBean SlackCommandDispatcher slackCommandDispatcher;

  // Slack ports stubbed so JdbcSlackWorkspaceRepository's @Component skips DataSource autowire.
  @MockitoBean SlackWorkspaceRepository slackWorkspaceRepository;
  @MockitoBean SlackWorkspaceWriteRepository slackWorkspaceWriteRepository;

  // Same mock set the other controller tests use to keep the api context bootable without
  // DataSource/Flyway. Copy-paste from AbstractControllerTest — kept self-contained so this
  // test is one-stop diagnosable.
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
  void rejectedWith401WhenSignatureFails() throws Exception {
    when(slackSigningVerifier.verify(any(), any(), any())).thenReturn(false);

    mvc.perform(
            post("/api/v1/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", "1717000000")
                .header("X-Slack-Signature", "v0=deadbeef")
                .content("team_id=T123&command=%2Farguslog&text=help"))
        .andExpect(status().isUnauthorized());

    verify(slackCommandDispatcher, never()).dispatch(any());
  }

  @Test
  void validSignatureDispatchesAndReturnsBlockKit() throws Exception {
    when(slackSigningVerifier.verify(any(), any(), any())).thenReturn(true);
    when(slackCommandDispatcher.dispatch(any()))
        .thenReturn(SlackCommandResponse.ephemeralText("hello from Arguslog"));

    mvc.perform(
            post("/api/v1/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", "1717000000")
                .header("X-Slack-Signature", "v0=goodsig")
                .content("team_id=T123&command=%2Farguslog&text=help"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.response_type").value("ephemeral"))
        .andExpect(jsonPath("$.text").value("hello from Arguslog"));
  }

  @Test
  void formFieldsArrivePreParsedAtTheDispatcher() throws Exception {
    when(slackSigningVerifier.verify(any(), any(), any())).thenReturn(true);
    when(slackCommandDispatcher.dispatch(any()))
        .thenReturn(SlackCommandResponse.ephemeralText("ok"));

    mvc.perform(
            post("/api/v1/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", "1717000000")
                .header("X-Slack-Signature", "v0=goodsig")
                .content(
                    "team_id=T123"
                        + "&team_domain=acme"
                        + "&channel_id=C9"
                        + "&user_id=U42"
                        + "&command=%2Farguslog"
                        + "&text=resolve%20987"
                        + "&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2Fxyz"))
        .andExpect(status().isOk());

    verify(slackCommandDispatcher)
        .dispatch(
            org.mockito.ArgumentMatchers.argThat(
                p ->
                    "T123".equals(p.teamId())
                        && "acme".equals(p.teamDomain())
                        && "C9".equals(p.channelId())
                        && "U42".equals(p.userId())
                        && "/arguslog".equals(p.command())
                        && "resolve 987".equals(p.text())
                        && p.responseUrl().startsWith("https://hooks.slack.com/")));
  }
}
