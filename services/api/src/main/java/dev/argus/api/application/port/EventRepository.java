package dev.argus.api.application.port;

import dev.argus.api.application.CursorCodec;
import dev.argus.api.domain.Event;
import java.util.List;
import java.util.Optional;

public interface EventRepository {

  /**
   * Returns up to {@code limit} events for the given issue (within the implicitly RLS-scoped
   * project), ordered {@code (received_at DESC, id DESC)}, optionally seeking strictly past {@code
   * cursor}.
   */
  List<Event> page(long issueId, Optional<CursorCodec.UuidCursor> cursor, int limit);
}
