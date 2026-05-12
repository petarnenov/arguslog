package org.arguslog.api.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.alerts.application.AlertDestinationUseCase;
import org.arguslog.api.alerts.application.AlertRuleUseCase;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertDestinationWriteRepository;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.alerts.application.port.AlertRuleWriteRepository;
import org.arguslog.api.application.DsnUseCase;
import org.arguslog.api.application.GetIssueUseCase;
import org.arguslog.api.application.ListIssueEventsUseCase;
import org.arguslog.api.application.ListIssuesUseCase;
import org.arguslog.api.application.MemberUseCase;
import org.arguslog.api.application.PlatformUseCase;
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
import org.arguslog.api.auth.application.PatUseCase;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.arguslog.api.email.InviteEmailSender;
import org.arguslog.api.releases.application.ReleaseUseCase;
import org.arguslog.api.releases.application.SourceMapArtifactUseCase;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.arguslog.api.tier.application.port.TierLookupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the production security filter chain (the {@code @Profile("!test")} bean in {@link
 * SecurityConfig}). Unlike {@code AbstractControllerTest}, this test does NOT activate the {@code
 * test} profile and therefore picks up the real PAT-then-JWT chain.
 *
 * <p>The bug this guards against: without the PAT-aware {@code BearerTokenResolver}, {@code
 * BearerTokenAuthenticationFilter} will resolve the {@code arglog_pat_*} bearer header, hand it to
 * the JWT decoder, fail with "Malformed token", and return 401 — even after {@code
 * PatAuthenticationFilter} has already authenticated the request. Asserting that {@link JwtDecoder}
 * is NEVER called for a PAT-prefixed bearer is the cheapest way to pin the fix in place; the
 * assertion would have caught the original misconfiguration on day one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
      // Issuer URI is harmless here because JwtDecoder is mocked — the value just has to parse.
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://localhost.test/issuer"
    })
class SecurityFilterChainIntegrationTest {

  // A path that requires authentication but no PAT scope, so we can test the auth flow without
  // worrying about scope checks.
  private static final String AUTHED_PATH = "/api/v1/orgs";

  @Autowired private MockMvc mvc;

  // The bean under test — we assert this is NOT invoked when an arglog_pat_* token comes in.
  @MockitoBean private JwtDecoder jwtDecoder;

  // We control PAT verification outcomes per test.
  @MockitoBean private PatUseCase patUseCase;

  // ── every other bean the api context demands so it can boot ───────────────────────────────
  @MockitoBean private ListIssuesUseCase listIssuesUseCase;
  @MockitoBean private ListIssueEventsUseCase listIssueEventsUseCase;
  @MockitoBean private GetIssueUseCase getIssueUseCase;
  @MockitoBean private AlertRuleUseCase alertRuleUseCase;
  @MockitoBean private AlertDestinationUseCase alertDestinationUseCase;
  @MockitoBean private ReleaseUseCase releaseUseCase;
  @MockitoBean private SourceMapArtifactUseCase sourceMapArtifactUseCase;
  @MockitoBean private MemberUseCase memberUseCase;
  @MockitoBean private PlatformUseCase platformUseCase;
  @MockitoBean private DsnUseCase dsnUseCase;

  @MockitoBean private IssueRepository issueRepository;
  @MockitoBean private EventRepository eventRepository;
  @MockitoBean private ProjectRepository projectRepository;
  @MockitoBean private ProjectWriteRepository projectWriteRepository;
  @MockitoBean private MembershipRepository membershipRepository;
  @MockitoBean private MembershipWriteRepository membershipWriteRepository;
  @MockitoBean private OrgWriteRepository orgWriteRepository;
  @MockitoBean private InviteEmailSender inviteEmailSender;
  @MockitoBean private DsnRepository dsnRepository;
  @MockitoBean private DsnWriteRepository dsnWriteRepository;
  @MockitoBean private PlatformRepository platformRepository;
  @MockitoBean private UserRepository userRepository;
  @MockitoBean private AlertRuleRepository alertRuleRepository;
  @MockitoBean private AlertRuleWriteRepository alertRuleWriteRepository;
  @MockitoBean private AlertDestinationRepository alertDestinationRepository;
  @MockitoBean private AlertDestinationWriteRepository alertDestinationWriteRepository;
  @MockitoBean private PatRepository patRepository;
  @MockitoBean private TokenHasher tokenHasher;
  @MockitoBean private ReleaseRepository releaseRepository;
  @MockitoBean private SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean private SourceMapArtifactWriteRepository sourceMapArtifactWriteRepository;
  @MockitoBean private SourceMapStorage sourceMapStorage;

  @MockitoBean private TierLookupRepository tierLookupRepository;
  @MockitoBean private org.arguslog.api.admin.application.port.AdminQueryPort adminQueryPort;

  @Test
  void validPatAuthenticatesAndJwtDecoderIsNeverCalled() throws Exception {
    String wire = "arglog_pat_AAAAAAAA_" + "x".repeat(48);
    when(patUseCase.verify(any(), any())).thenReturn(Optional.of(token()));

    // The path eventually reaches MemberController.listMyOrgs; with PatAuthentication on the
    // SecurityContext the controller runs (we don't care about its body here — only the auth
    // outcome). Without the fix, this would 401 because the JWT decoder rejected the PAT.
    mvc.perform(get(AUTHED_PATH).header("Authorization", "Bearer " + wire))
        .andExpect(status().is(org.hamcrest.Matchers.not(org.hamcrest.Matchers.is(401))));

    verify(jwtDecoder, never()).decode(any());
  }

  @Test
  void invalidPatReturns401WithoutEverInvokingJwtDecoder() throws Exception {
    String wire = "arglog_pat_BBBBBBBB_" + "y".repeat(48);
    when(patUseCase.verify(any(), any())).thenReturn(Optional.empty());

    mvc.perform(get(AUTHED_PATH).header("Authorization", "Bearer " + wire))
        .andExpect(status().isUnauthorized());

    // Crucially, we did not slip into the JWT path even though auth failed — that path would
    // have produced a misleading "Malformed token" error_description on the response.
    verify(jwtDecoder, never()).decode(any());
  }

  @Test
  void nonPatBearerTokenIsHandedToJwtDecoder() throws Exception {
    // A bearer that does NOT start with arglog_pat_ — the resolver should hand it to the JWT
    // decoder. Whatever the decoder returns determines the response; we only care about wiring,
    // so we make the decoder throw a Spring-Security-recognised JWT exception and assert that
    // it was invoked. The request status itself is incidental.
    when(jwtDecoder.decode(any())).thenThrow(new BadJwtException("test: not a valid jwt"));

    try {
      mvc.perform(get(AUTHED_PATH).header("Authorization", "Bearer eyJpc3MiOiJ0ZXN0In0.fake.sig"));
    } catch (Exception ignored) {
      // MockMvc surfaces filter-chain exceptions; a real servlet container would convert this
      // into a 401 via BearerTokenAuthenticationEntryPoint. Either way, the decoder was called.
    }

    verify(jwtDecoder).decode(any());
  }

  private static PersonalAccessToken token() {
    return new PersonalAccessToken(
        7L,
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "ci",
        "AAAAAAAA",
        null,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Set.of(PatScope.values()));
  }
}
