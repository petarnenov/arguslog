package org.arguslog.worker.adapter.out.postgres;

import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.worker.application.port.SymbolicationRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Two-hop join from {@code (project_id, release version, original path) → r2_key}. The api owns RLS
 * on the source tables; the worker writes events for every tenant so we deliberately do NOT pin
 * {@code arguslog.org_id} here — the join already constrains by {@code project_id} which is the
 * security boundary.
 */
@Component
public class JdbcSymbolicationRepository implements SymbolicationRepository {

  private static final String SQL =
      """
      SELECT s.release_id, s.r2_key, s.sha256
        FROM source_map_artifacts s
        JOIN releases r ON r.id = s.release_id
       WHERE r.project_id = ? AND r.version = ? AND s.original_path = ?
      """;

  private final JdbcTemplate jdbc;

  public JdbcSymbolicationRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<ArtifactRow> findArtifact(
      long projectId, String releaseVersion, String originalPath) {
    try {
      ArtifactRow row =
          jdbc.queryForObject(
              SQL,
              (rs, rowNum) ->
                  new ArtifactRow(
                      rs.getLong("release_id"), rs.getString("r2_key"), rs.getString("sha256")),
              projectId,
              releaseVersion,
              originalPath);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
