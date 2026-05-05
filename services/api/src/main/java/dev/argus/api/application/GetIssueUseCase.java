package dev.argus.api.application;

import dev.argus.api.domain.Issue;
import java.util.Optional;

public interface GetIssueUseCase {
  Optional<Issue> get(long projectId, long issueId);
}
