package org.arguslog.api.adapter.out.git;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the unauthenticated public-API clients used by the "Create release" form's branch
 * dropdown. Per-host base URLs are overridable to keep tests (WireMock) decoupled from github.com /
 * gitlab.com.
 *
 * <p>Timeouts and cache TTL are shared across providers — the requests are tiny, infrequent, and
 * driven by UI clicks, so there's no reason to tune them differently.
 */
@ConfigurationProperties(prefix = "arguslog.git.public")
public record GitPublicProperties(
    String githubApiBaseUrl, String gitlabApiBaseUrl, Duration timeout, Duration cacheTtl) {

  public GitPublicProperties {
    if (githubApiBaseUrl == null || githubApiBaseUrl.isBlank()) {
      githubApiBaseUrl = "https://api.github.com";
    }
    if (gitlabApiBaseUrl == null || gitlabApiBaseUrl.isBlank()) {
      gitlabApiBaseUrl = "https://gitlab.com/api/v4";
    }
    if (timeout == null) timeout = Duration.ofSeconds(5);
    if (cacheTtl == null) cacheTtl = Duration.ofSeconds(60);
  }
}
