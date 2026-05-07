package org.arguslog.api.auth.adapter.out.postgres;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.domain.PatScope;
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
      UUID userId,
      String name,
      String prefix,
      String tokenHash,
      Instant expiresAt,
      Set<PatScope> scopes) {
    // Need a JDBC Array for the TEXT[] column — easier to construct via the live connection
    // than to map through JdbcTemplate's typed-args overload. Wrap in execute so Hikari closes
    // the borrowed conn cleanly.
    return jdbc.execute(
        (Connection conn) -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  """
                  INSERT INTO personal_access_tokens
                      (user_id, name, prefix, token_hash, expires_at, scopes)
                  VALUES (?, ?, ?, ?, ?, ?)
                  RETURNING id, user_id, name, prefix, expires_at, last_used_at, created_at, scopes
                  """)) {
            ps.setObject(1, userId);
            ps.setString(2, name);
            ps.setString(3, prefix);
            ps.setString(4, tokenHash);
            if (expiresAt == null) {
              ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
              ps.setTimestamp(5, Timestamp.from(expiresAt));
            }
            if (scopes == null || scopes.isEmpty()) {
              ps.setNull(6, Types.ARRAY);
            } else {
              ps.setArray(
                  6,
                  conn.createArrayOf(
                      "text", scopes.stream().map(PatScope::wire).toArray(String[]::new)));
            }
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapToken(rs, 0);
            }
          }
        });
  }

  @Override
  public List<PersonalAccessToken> listForUser(UUID userId) {
    return jdbc.query(
        """
        SELECT id, user_id, name, prefix, expires_at, last_used_at, created_at, scopes
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
              SELECT id, user_id, name, prefix, expires_at, last_used_at, created_at, scopes,
                     token_hash
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
        rs.getTimestamp("created_at").toInstant(),
        readScopes(rs));
  }

  private static Set<PatScope> readScopes(ResultSet rs) throws SQLException {
    Array array = rs.getArray("scopes");
    if (array == null) return null;
    String[] raw = (String[]) array.getArray();
    if (raw.length == 0) return null;
    Set<PatScope> out = new LinkedHashSet<>(raw.length);
    for (String wire : raw) {
      try {
        out.add(PatScope.fromWire(wire));
      } catch (IllegalArgumentException ignored) {
        // Unknown wire → skip. Likely a scope removed in a later refactor; treat the row as
        // not granting that scope rather than blowing up the request.
      }
    }
    return out.isEmpty() ? null : out;
  }
}
