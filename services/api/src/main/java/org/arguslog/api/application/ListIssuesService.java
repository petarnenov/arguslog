package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.application.CursorCodec.LongCursor;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Issue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListIssuesService implements ListIssuesUseCase {

  private final IssueRepository repository;

  public ListIssuesService(IssueRepository repository) {
    this.repository = repository;
  }

  // Read-only TX so the SET LOCAL inside JdbcIssueRepository.pinOrgContextForRls scopes to this
  // request, and PG can route to a hot-standby when we add one in P5.
  @Override
  @Transactional(readOnly = true)
  public Page list(Query query) {
    Optional<LongCursor> cursor = query.cursor().map(CursorCodec::decodeLong);

    // N+1 trick: ask for one more than requested; if we got it, there is a next page.
    List<Issue> rows =
        repository.page(
            query.projectId(),
            query.status(),
            query.level(),
            query.searchText(),
            query.assignee(),
            cursor,
            query.limit() + 1);

    boolean hasMore = rows.size() > query.limit();
    List<Issue> page = hasMore ? rows.subList(0, query.limit()) : rows;
    Optional<String> next =
        hasMore
            ? Optional.of(
                CursorCodec.encodeLong(
                    page.get(page.size() - 1).lastSeenAt(), page.get(page.size() - 1).id()))
            : Optional.empty();

    return new Page(page, next);
  }
}
