package org.arguslog.api.application;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.application.CursorCodec.UuidCursor;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.domain.Event;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListIssueEventsService implements ListIssueEventsUseCase {

  private final IssueRepository issues;
  private final EventRepository events;

  public ListIssueEventsService(IssueRepository issues, EventRepository events) {
    this.issues = issues;
    this.events = events;
  }

  @Override
  @Transactional(readOnly = true)
  public Page list(Query query) {
    // Verify the issue lives under the path's project before opening the (potentially large)
    // events fetch — same Sentry 404-not-403 contract as the access guard. We return an empty
    // sealed Page rather than throwing so the controller can map "no such issue" to 404 itself.
    if (issues.findByProjectAndId(query.projectId(), query.issueId()).isEmpty()) {
      return new Page(List.of(), Optional.empty());
    }

    Optional<UuidCursor> cursor = query.cursor().map(CursorCodec::decodeUuid);
    List<Event> rows = events.page(query.issueId(), cursor, query.limit() + 1);

    boolean hasMore = rows.size() > query.limit();
    List<Event> page = hasMore ? rows.subList(0, query.limit()) : rows;
    Optional<String> next =
        hasMore
            ? Optional.of(
                CursorCodec.encodeUuid(
                    page.get(page.size() - 1).receivedAt(), page.get(page.size() - 1).id()))
            : Optional.empty();
    return new Page(page, next);
  }
}
