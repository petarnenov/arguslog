package org.arguslog.api.application.port;

import java.util.List;
import java.util.Optional;
import org.arguslog.api.application.CursorCodec;
import org.arguslog.api.domain.Event;

public interface EventRepository {

  /**
   * Returns up to {@code limit} events for the given issue (within the implicitly RLS-scoped
   * project), ordered {@code (received_at DESC, id DESC)}, optionally seeking strictly past {@code
   * cursor}.
   */
  List<Event> page(long issueId, Optional<CursorCodec.UuidCursor> cursor, int limit);
}
