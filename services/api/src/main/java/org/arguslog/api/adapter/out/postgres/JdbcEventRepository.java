package org.arguslog.api.adapter.out.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.CursorCodec;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.domain.Event;
import org.arguslog.api.security.OrgContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

@Component
public class JdbcEventRepository implements EventRepository {

  // (received_at, id) tuple comparison handles UUID id ordering correctly in PG.
  // Tightly
  // bounded to issue_id so the chunk planner stays selective on Timescale.
  private static final String PAGE_SQL = """
      SELECT id, issue_id, project_id, received_at, payload::text AS payload_json
        FROM events
       WHERE issue_id = ?
         AND (? IS NULL OR (received_at, id) < (?::timestamptz, ?::uuid))
       ORDER BY received_at DESC, id DESC
       LIMIT ?
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcEventRepository(DataSource dataSource, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.mapper = mapper;
  }

  @Override
  public List<Event> page(long issueId, Optional<CursorCodec.UuidCursor> cursor, int limit) {
    Object cursorTs = cursor.map(c -> java.sql.Timestamp.from(c.instant())).orElse(null);
    Object cursorId = cursor.map(c -> c.id().toString()).orElse(null);
    Object cursorPresence = cursor.isPresent() ? Boolean.TRUE : null;

    Object[] args = { issueId, cursorPresence, cursorTs, cursorId, limit };
    int[] types = { Types.BIGINT, Types.BOOLEAN, Types.TIMESTAMP, Types.OTHER, Types.INTEGER };

    pinOrgContextForRls();
    List<Event> out = new ArrayList<>(limit);
    RowCallbackHandler handler = rs -> {
      JsonNode payload;
      try {
        payload = mapper.readTree(rs.getString("payload_json"));
      } catch (IOException e) {
        throw new IllegalStateException(
            "events.payload was not valid JSON for event " + rs.getString("id"), e);
      }
      out.add(
          new Event(
              UUID.fromString(rs.getString("id")),
              rs.getLong("issue_id"),
              rs.getLong("project_id"),
              rs.getTimestamp("received_at").toInstant(),
              payload));
    };
    jdbc.query(PAGE_SQL, args, types, handler);
    return out;
  }

  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }
}
