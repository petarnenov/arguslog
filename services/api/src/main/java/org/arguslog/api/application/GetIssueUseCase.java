package org.arguslog.api.application;

import org.arguslog.api.domain.Issue;
import java.util.Optional;

public interface GetIssueUseCase {
  Optional<Issue> get(long projectId, long issueId);
}
