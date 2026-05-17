package org.arguslog.api.application;

import java.util.List;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Show me every issue introduced in this specific release" — the regression-watchlist surface the
 * dashboard renders on the release detail page. Behaviour: validate the release exists in the
 * project (so the controller surfaces 404 for unknown ids instead of a vacuous empty list), then
 * page-once through the issues table filtered by {@code first_seen_release_id}.
 *
 * <p>The release-exists check matters because an empty list otherwise looks identical to "release
 * exists but had clean shipping" vs. "release id is bogus" — distinguishing the two is what gives
 * the UI a real 404.
 */
@Service
public class IssuesByReleaseUseCase {

  /** Defensive upper bound — per-release lists are expected to be tens, not thousands. */
  static final int LIMIT = 200;

  private final IssueRepository issues;
  private final ReleaseRepository releases;

  public IssuesByReleaseUseCase(IssueRepository issues, ReleaseRepository releases) {
    this.issues = issues;
    this.releases = releases;
  }

  @Transactional(readOnly = true)
  public List<Issue> list(long projectId, long releaseId) {
    releases
        .find(projectId, releaseId)
        .orElseThrow(
            () ->
                new ReleaseNotFoundException(
                    "release " + releaseId + " not found in project " + projectId));
    return issues.listIntroducedInRelease(projectId, releaseId, LIMIT);
  }

  /** Thrown when the release id is not present under the given project. Controller → 404. */
  public static final class ReleaseNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReleaseNotFoundException(String message) {
      super(message);
    }
  }
}
