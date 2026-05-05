package org.arguslog.api.application;

import java.util.Optional;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetIssueService implements GetIssueUseCase {

  private final IssueRepository repository;

  public GetIssueService(IssueRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Issue> get(long projectId, long issueId) {
    return repository.findByProjectAndId(projectId, issueId);
  }
}
