package org.arguslog.api.application;

import java.util.Optional;
import org.arguslog.api.domain.Issue;

public interface GetIssueUseCase {
  Optional<Issue> get(long projectId, long issueId);
}
