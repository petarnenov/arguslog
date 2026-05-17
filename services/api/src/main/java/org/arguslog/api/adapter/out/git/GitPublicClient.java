package org.arguslog.api.adapter.out.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.arguslog.api.domain.GitProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Unauthenticated public-API client for the Git hosts that drive the "Create release" branch
 * dropdown. One class with provider-aware dispatch instead of a strategy interface — at exactly two
 * providers the dispatch is a switch, and the JSON shapes differ enough that a shared abstract base
 * would be more obfuscating than illuminating. When a third provider arrives, this is the right
 * time to extract.
 *
 * <p>Trust posture: GET-only, no Authorization header, no SDK. Cache hits avoid the unauthenticated
 * rate budget (60 req/h/IP on GitHub, ~300 req/min on GitLab) when a whole team has the release
 * modal open simultaneously.
 */
@Component
public class GitPublicClient {

  private static final Logger log = LoggerFactory.getLogger(GitPublicClient.class);

  /** Result of {@link #listBranches(GitProvider, String)}. */
  public sealed interface BranchListResult {
    /** 200 OK — {@code branches} is the parsed list (may be empty for a repo with no branches). */
    record Ok(List<Branch> branches) implements BranchListResult {}

    /** 404 — the repo doesn't exist publicly (could be typo'd or private). */
    record NotFound() implements BranchListResult {}

    /** 403 / 429 — IP budget exhausted on the public unauthenticated tier. */
    record RateLimited(Instant resetAt) implements BranchListResult {}

    /** I/O, parse, or any other non-mappable failure. */
    record TransportError(String message) implements BranchListResult {}
  }

  public record Branch(String name, String sha) {}

  private final GitPublicProperties props;
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final Clock clock;
  private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

  @Autowired
  public GitPublicClient(GitPublicProperties props, ObjectMapper mapper) {
    this(
        props,
        mapper,
        HttpClient.newBuilder().connectTimeout(props.timeout()).build(),
        Clock.systemUTC());
  }

  /** Test ctor — caller supplies a WireMock-backed HttpClient and a fixed clock. */
  GitPublicClient(GitPublicProperties props, ObjectMapper mapper, HttpClient http, Clock clock) {
    this.props = props;
    this.mapper = mapper;
    this.http = http;
    this.clock = clock;
  }

  /**
   * Fetches branches for {@code repo} on {@code provider}. {@code repo} is the canonical path
   * stored in {@code projects.git_repo} ({@code owner/repo} for GitHub, {@code group/project} or
   * nested {@code group/sub/project} for GitLab). Results are cached in-memory per process for
   * {@link GitPublicProperties#cacheTtl()}; rate-limit and transport errors are intentionally NOT
   * cached so the next click can retry.
   */
  public BranchListResult listBranches(GitProvider provider, String repo) {
    CacheKey key = new CacheKey(provider, repo);
    CacheEntry cached = cache.get(key);
    Instant now = clock.instant();
    if (cached != null && cached.expiresAt.isAfter(now)) {
      return cached.result;
    }
    BranchListResult fresh = fetch(provider, repo);
    if (fresh instanceof BranchListResult.Ok || fresh instanceof BranchListResult.NotFound) {
      cache.put(key, new CacheEntry(fresh, now.plus(props.cacheTtl())));
    }
    return fresh;
  }

  private BranchListResult fetch(GitProvider provider, String repo) {
    URI url =
        switch (provider) {
          case GITHUB ->
              URI.create(props.githubApiBaseUrl() + "/repos/" + repo + "/branches?per_page=100");
          case GITLAB ->
              URI.create(
                  props.gitlabApiBaseUrl()
                      + "/projects/"
                      + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                      + "/repository/branches?per_page=100");
        };
    HttpRequest.Builder b =
        HttpRequest.newBuilder(url).timeout(props.timeout()).header("User-Agent", "arguslog").GET();
    if (provider == GitProvider.GITHUB) {
      b.header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", "2022-11-28");
    } else {
      b.header("Accept", "application/json");
    }
    HttpResponse<String> resp;
    try {
      resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      log.warn(
          "{} public branches transport failure for {}: {}",
          provider.dbValue(),
          repo,
          e.getMessage());
      return new BranchListResult.TransportError("transport_error");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new BranchListResult.TransportError("interrupted");
    }
    int status = resp.statusCode();
    if (status == 404) return new BranchListResult.NotFound();
    if (status == 403 || status == 429) {
      return new BranchListResult.RateLimited(parseRateLimitReset(provider, resp));
    }
    if (status / 100 != 2) {
      log.warn("{} public branches non-2xx for {}: HTTP {}", provider.dbValue(), repo, status);
      return new BranchListResult.TransportError("http_" + status);
    }
    JsonNode array;
    try {
      array = mapper.readTree(resp.body());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new BranchListResult.TransportError("parse_error");
    }
    if (!array.isArray()) {
      return new BranchListResult.TransportError("unexpected_shape");
    }
    List<Branch> branches = new ArrayList<>(array.size());
    for (JsonNode node : array) {
      String name = node.path("name").asText(null);
      // GitHub: commit.sha. GitLab: commit.id. Both are 40-char hex SHAs.
      String sha =
          node.path("commit").path(provider == GitProvider.GITHUB ? "sha" : "id").asText(null);
      if (name != null && sha != null) branches.add(new Branch(name, sha));
    }
    return new BranchListResult.Ok(branches);
  }

  /**
   * GitHub uses {@code X-RateLimit-Reset} (epoch seconds). GitLab uses {@code RateLimit-Reset}
   * (epoch seconds, no X-prefix on the modern API) and/or {@code Retry-After} (relative seconds).
   * Fall back to "now + 60s" if we can't parse either — the UI just needs an "approximate reset
   * time" for the error message, not a precise schedule.
   */
  private Instant parseRateLimitReset(GitProvider provider, HttpResponse<String> resp) {
    String[] keys =
        provider == GitProvider.GITHUB
            ? new String[] {"X-RateLimit-Reset", "x-ratelimit-reset"}
            : new String[] {"RateLimit-Reset", "ratelimit-reset"};
    for (String key : keys) {
      var maybe = resp.headers().firstValue(key);
      if (maybe.isPresent()) {
        try {
          return Instant.ofEpochSecond(Long.parseLong(maybe.get()));
        } catch (NumberFormatException ignored) {
          // fall through
        }
      }
    }
    // GitLab's Retry-After is a relative-seconds count.
    if (provider == GitProvider.GITLAB) {
      var retryAfter = resp.headers().firstValue("Retry-After");
      if (retryAfter.isPresent()) {
        try {
          return clock.instant().plusSeconds(Long.parseLong(retryAfter.get()));
        } catch (NumberFormatException ignored) {
          // fall through
        }
      }
    }
    return clock.instant().plusSeconds(60);
  }

  private record CacheKey(GitProvider provider, String repo) {}

  private record CacheEntry(BranchListResult result, Instant expiresAt) {}
}
