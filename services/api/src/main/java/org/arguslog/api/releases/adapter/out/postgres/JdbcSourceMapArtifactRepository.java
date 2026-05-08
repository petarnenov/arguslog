package org.arguslog.api.releases.adapter.out.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactWriteRepository;
import org.arguslog.api.releases.domain.SourceMapArtifact;
import org.arguslog.api.security.OrgContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSourceMapArtifactRepository
    implements SourceMapArtifactRepository, SourceMapArtifactWriteRepository {

  private final JdbcTemplate jdbc;
  private final RowMapper<SourceMapArtifact> rowMapper = this::mapRow;

  public JdbcSourceMapArtifactRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public SourceMapArtifact upsert(
      long releaseId, String r2Key, String originalPath, String sha256, long sizeBytes) {
    pinOrgContextForRls();
    // Replace-on-conflict so a CLI re-upload after rebuild doesn't accumulate stale rows.
    return jdbc.queryForObject(
        """
        INSERT INTO source_map_artifacts (release_id, r2_key, original_path, sha256, size_bytes)
             VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (release_id, original_path)
            DO UPDATE SET r2_key = EXCLUDED.r2_key,
                          sha256 = EXCLUDED.sha256,
                          size_bytes = EXCLUDED.size_bytes,
                          created_at = NOW()
          RETURNING id, release_id, r2_key, original_path, sha256, size_bytes, created_at
        """,
        new Object[] {releaseId, r2Key, originalPath, sha256, sizeBytes},
        new int[] {Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT},
        rowMapper);
  }

  @Override
  public List<SourceMapArtifact> listForRelease(long releaseId) {
    pinOrgContextForRls();
    return jdbc.query(
        """
        SELECT id, release_id, r2_key, original_path, sha256, size_bytes, created_at
          FROM source_map_artifacts
         WHERE release_id = ?
         ORDER BY original_path ASC, id ASC
        """,
        rowMapper,
        releaseId);
  }

  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }

  private SourceMapArtifact mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new SourceMapArtifact(
        rs.getLong("id"),
        rs.getLong("release_id"),
        rs.getString("r2_key"),
        rs.getString("original_path"),
        rs.getString("sha256"),
        rs.getLong("size_bytes"),
        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
  }
}
