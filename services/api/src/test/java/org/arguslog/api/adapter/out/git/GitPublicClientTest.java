package org.arguslog.api.adapter.out.git;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.arguslog.api.adapter.out.git.GitPublicClient.BranchListResult;
import org.arguslog.api.domain.GitProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitPublicClientTest {

  private WireMockServer wm;
  private GitPublicClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() {
    wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wm.start();
    GitPublicProperties props =
        new GitPublicProperties(
            wm.baseUrl(), wm.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(60));
    client =
        new GitPublicClient(
            props,
            mapper,
            HttpClient.newHttpClient(),
            Clock.fixed(Instant.parse("2026-05-17T12:00:00Z"), ZoneOffset.UTC));
  }

  @AfterEach
  void stop() {
    wm.stop();
  }

  @Test
  void githubParses200IntoBranches() {
    wm.stubFor(
        get(urlPathEqualTo("/repos/acme/widgets/branches"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        [
                          {"name": "main", "commit": {"sha": "abc1234"}},
                          {"name": "dev",  "commit": {"sha": "def5678"}}
                        ]
                        """)));

    BranchListResult r = client.listBranches(GitProvider.GITHUB, "acme/widgets");

    assertThat(r).isInstanceOf(BranchListResult.Ok.class);
    var ok = (BranchListResult.Ok) r;
    assertThat(ok.branches())
        .extracting(GitPublicClient.Branch::name, GitPublicClient.Branch::sha)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("main", "abc1234"),
            org.assertj.core.groups.Tuple.tuple("dev", "def5678"));
  }

  @Test
  void gitlabParses200IntoBranchesAndReadsCommitId() {
    // GitLab path-encodes the project (group%2Fproject) and returns commit.id, not commit.sha.
    wm.stubFor(
        get(urlPathEqualTo("/projects/acme%2Fwidgets/repository/branches"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        [
                          {"name": "main",    "commit": {"id": "11112222"}},
                          {"name": "feature", "commit": {"id": "33334444"}}
                        ]
                        """)));

    BranchListResult r = client.listBranches(GitProvider.GITLAB, "acme/widgets");

    assertThat(r).isInstanceOf(BranchListResult.Ok.class);
    var ok = (BranchListResult.Ok) r;
    assertThat(ok.branches())
        .extracting(GitPublicClient.Branch::name, GitPublicClient.Branch::sha)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("main", "11112222"),
            org.assertj.core.groups.Tuple.tuple("feature", "33334444"));
  }

  @Test
  void gitlabUrlEncodesNestedGroupPath() {
    wm.stubFor(
        get(urlPathEqualTo("/projects/group%2Fsub%2Fproject/repository/branches"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"name\":\"main\",\"commit\":{\"id\":\"aaaa\"}}]")));

    BranchListResult r = client.listBranches(GitProvider.GITLAB, "group/sub/project");
    assertThat(r).isInstanceOf(BranchListResult.Ok.class);
  }

  @Test
  void maps404ToNotFoundForBothProviders() {
    wm.stubFor(
        get(urlPathEqualTo("/repos/nope/missing/branches"))
            .willReturn(aResponse().withStatus(404).withBody("{}")));
    wm.stubFor(
        get(urlPathEqualTo("/projects/nope%2Fmissing/repository/branches"))
            .willReturn(aResponse().withStatus(404).withBody("{}")));

    assertThat(client.listBranches(GitProvider.GITHUB, "nope/missing"))
        .isInstanceOf(BranchListResult.NotFound.class);
    assertThat(client.listBranches(GitProvider.GITLAB, "nope/missing"))
        .isInstanceOf(BranchListResult.NotFound.class);
  }

  @Test
  void mapsGithub403WithResetHeaderToRateLimited() {
    wm.stubFor(
        get(urlPathEqualTo("/repos/acme/widgets/branches"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("X-RateLimit-Reset", "1893456000")
                    .withBody("{\"message\":\"rate limit\"}")));

    BranchListResult r = client.listBranches(GitProvider.GITHUB, "acme/widgets");

    assertThat(r).isInstanceOf(BranchListResult.RateLimited.class);
    var rl = (BranchListResult.RateLimited) r;
    assertThat(rl.resetAt()).isEqualTo(Instant.ofEpochSecond(1893456000L));
  }

  @Test
  void mapsGitlab429WithRetryAfterToRateLimited() {
    wm.stubFor(
        get(urlPathEqualTo("/projects/acme%2Fwidgets/repository/branches"))
            .willReturn(
                aResponse().withStatus(429).withHeader("Retry-After", "30").withBody("{}")));

    BranchListResult r = client.listBranches(GitProvider.GITLAB, "acme/widgets");

    assertThat(r).isInstanceOf(BranchListResult.RateLimited.class);
    var rl = (BranchListResult.RateLimited) r;
    assertThat(rl.resetAt()).isEqualTo(Instant.parse("2026-05-17T12:00:30Z"));
  }

  @Test
  void maps5xxToTransportError() {
    wm.stubFor(
        get(urlPathEqualTo("/repos/acme/widgets/branches"))
            .willReturn(aResponse().withStatus(500)));

    assertThat(client.listBranches(GitProvider.GITHUB, "acme/widgets"))
        .isInstanceOf(BranchListResult.TransportError.class);
  }

  @Test
  void cachesSuccessfulResponsePerProviderAndRepo() {
    wm.stubFor(
        get(urlPathEqualTo("/repos/acme/widgets/branches"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"name\":\"main\",\"commit\":{\"sha\":\"abc\"}}]")));
    wm.stubFor(
        get(urlPathEqualTo("/projects/acme%2Fwidgets/repository/branches"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"name\":\"main\",\"commit\":{\"id\":\"def\"}}]")));

    client.listBranches(GitProvider.GITHUB, "acme/widgets");
    client.listBranches(GitProvider.GITHUB, "acme/widgets");
    client.listBranches(GitProvider.GITLAB, "acme/widgets");
    client.listBranches(GitProvider.GITLAB, "acme/widgets");

    // (provider, repo) is the cache key, so each pair fires exactly once even though the repo
    // path string is identical across providers.
    assertThat(wm.findAll(getRequestedFor(urlPathEqualTo("/repos/acme/widgets/branches"))))
        .hasSize(1);
    assertThat(
            wm.findAll(
                getRequestedFor(urlPathEqualTo("/projects/acme%2Fwidgets/repository/branches"))))
        .hasSize(1);
  }

  @Test
  void doesNotCacheRateLimitedSoNextClickRetries() {
    wm.stubFor(
        get(urlPathEqualTo("/repos/acme/widgets/branches"))
            .willReturn(aResponse().withStatus(403).withHeader("X-RateLimit-Reset", "1893456000")));

    client.listBranches(GitProvider.GITHUB, "acme/widgets");
    client.listBranches(GitProvider.GITHUB, "acme/widgets");

    assertThat(wm.findAll(getRequestedFor(urlPathEqualTo("/repos/acme/widgets/branches"))))
        .hasSize(2);
  }
}
