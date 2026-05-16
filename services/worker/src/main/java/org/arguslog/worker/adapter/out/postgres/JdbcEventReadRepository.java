package org.arguslog.worker.adapter.out.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.EventReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single-method read port over the {@code events} table. The events table is a Timescale
 * hypertable partitioned on {@code received_at}; the (issue_id, received_at DESC) lookup hits the
 * default chunk index. We don't paginate — the only consumer (GithubIssueAlertDispatcher) wants
 * just the most recent event for an issue, full stop.
 */
@Component
public class JdbcEventReadRepository implements EventReadRepository {

  private static final Logger log = LoggerFactory.getLogger(JdbcEventReadRepository.class);

  private static final String LATEST_PAYLOAD_SQL =
      """
      SELECT payload
        FROM events
       WHERE project_id = ?
         AND issue_id   = ?
       ORDER BY received_at DESC
       LIMIT 1
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcEventReadRepository(DataSource dataSource, ObjectMapper json) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.json = json;
  }

  @Override
  public Optional<JsonNode> findLatestPayloadForIssue(long projectId, long issueId) {
    try {
      String raw =
          jdbc.queryForObject(LATEST_PAYLOAD_SQL, String.class, projectId, issueId);
      if (raw == null) return Optional.empty();
      return Optional.of(json.readTree(raw));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    } catch (JsonProcessingException e) {
      // Hit if a row got persisted with malformed JSON (shouldn't happen — write path goes
      // through Jackson too). Treat as "no payload" rather than failing the entire dispatch.
      log.warn(
          "events.payload for issue {} (project {}) is not valid JSON: {}",
          issueId,
          projectId,
          e.getMessage());
      return Optional.empty();
    }
  }
}
