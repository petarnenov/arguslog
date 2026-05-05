package org.arguslog.api.application;

import org.arguslog.api.domain.Event;
import java.util.List;
import java.util.Optional;

public interface ListIssueEventsUseCase {

  Page list(Query query);

  record Query(long projectId, long issueId, Optional<String> cursor, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public Query {
      if (limit <= 0) limit = DEFAULT_LIMIT;
      if (limit > MAX_LIMIT) limit = MAX_LIMIT;
    }
  }

  record Page(List<Event> events, Optional<String> nextCursor) {}
}
