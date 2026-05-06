package org.arguslog.api.auth.adapter.out.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcPatRepository implements PatRepository {

  private final JdbcTemplate jdbc;
  private final RowMapper<PersonalAccessToken> tokenMapper = JdbcPatRepository::mapToken;

  public JdbcPatRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public PersonalAccessToken create(
      UUID userId, String name, String prefix, String tokenHash, Instant expiresAt) {
    return jdbc.queryForObject(
        """
        INSERT INTO personal_access_tokens
            (user_id, name, prefix, token_hash, expires_at)
        VALUES (?, ?, ?, ?, ?)
        RETURNING id, user_id, name, prefix, expires_at, last_used_at, created_at
        """,
        new Object[] {
          userId, name, prefix, tokenHash, expiresAt == null ? null : Timestamp.from(expiresAt)
        },
        new int[] {
          Types.OTHER, Types.VARCHAR, Types.CHAR, Types.VARCHAR, Types.TIMESTAMP_WITH_TIMEZONE
        },
        tokenMapper);
  }

  @Override
  public List<PersonalAccessToken> listForUser(UUID userId) {
    return jdbc.query(
        """
        SELECT id, user_id, name, prefix, expires_at, last_used_at, created_at
          FROM personal_access_tokens
         WHERE user_id = ?
         ORDER BY created_at DESC, id DESC
        """,
        tokenMapper,
        userId);
  }

  @Override
  public Optional<PatRow> findByPrefix(String prefix) {
    try {
      PatRow row =
          jdbc.queryForObject(
              """
              SELECT id, user_id, name, prefix, expires_at, last_used_at, created_at, token_hash
                FROM personal_access_tokens
               WHERE prefix = ?
              """,
              (rs, n) -> new PatRow(mapToken(rs, n), rs.getString("token_hash")),
              prefix);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public void recordUsage(long id, Instant when) {
    jdbc.update(
        "UPDATE personal_access_tokens SET last_used_at = ? WHERE id = ?",
        Timestamp.from(when),
        id);
  }

  @Override
  public boolean deleteForUser(UUID userId, long id) {
    return jdbc.update(
            "DELETE FROM personal_access_tokens WHERE user_id = ? AND id = ?", userId, id)
        > 0;
  }

  private static PersonalAccessToken mapToken(ResultSet rs, int rowNum) throws SQLException {
    Timestamp expires = rs.getTimestamp("expires_at");
    Timestamp lastUsed = rs.getTimestamp("last_used_at");
    return new PersonalAccessToken(
        rs.getLong("id"),
        (UUID) rs.getObject("user_id"),
        rs.getString("name"),
        rs.getString("prefix").trim(),
        expires == null ? null : expires.toInstant(),
        lastUsed == null ? null : lastUsed.toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }
}
