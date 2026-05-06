package org.arguslog.api.alerts.adapter.out.postgres;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.SecretCipher;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;
import org.arguslog.api.security.OrgContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcAlertDestinationRepository implements AlertDestinationRepository {

  private final JdbcTemplate jdbc;
  private final SecretCipher cipher;
  private final RowMapper<AlertDestination> rowMapper = this::mapRow;

  public JdbcAlertDestinationRepository(DataSource dataSource, SecretCipher cipher) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.cipher = cipher;
  }

  @Override
  public AlertDestination create(long orgId, DestinationKind kind, String name, String configJson) {
    pinOrgContextForRls();
    byte[] encrypted = cipher.encrypt(configJson.getBytes(StandardCharsets.UTF_8));
    return jdbc.queryForObject(
        """
            INSERT INTO alert_destinations (org_id, kind, name, config_encrypted)
                 VALUES (?, ?::destination_kind, ?, ?)
              RETURNING id, org_id, kind::text, name, config_encrypted, created_at
            """,
        rowMapper,
        orgId,
        kind.dbValue(),
        name,
        encrypted);
  }

  @Override
  public List<AlertDestination> listForOrg(long orgId) {
    pinOrgContextForRls();
    return jdbc.query(
        """
            SELECT id, org_id, kind::text, name, config_encrypted, created_at
              FROM alert_destinations
             WHERE org_id = ?
             ORDER BY created_at DESC, id DESC
            """,
        rowMapper,
        orgId);
  }

  @Override
  public Optional<AlertDestination> find(long orgId, long id) {
    pinOrgContextForRls();
    try {
      return Optional.ofNullable(
          jdbc.queryForObject(
              """
                  SELECT id, org_id, kind::text, name, config_encrypted, created_at
                    FROM alert_destinations
                   WHERE org_id = ? AND id = ?
                  """,
              rowMapper,
              orgId,
              id));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<AlertDestination> update(long orgId, long id, String name, String configJson) {
    pinOrgContextForRls();
    byte[] encrypted = cipher.encrypt(configJson.getBytes(StandardCharsets.UTF_8));
    int updated =
        jdbc.update(
            """
            UPDATE alert_destinations
               SET name = ?, config_encrypted = ?
             WHERE org_id = ? AND id = ?
            """,
            name,
            encrypted,
            orgId,
            id);
    if (updated == 0) return Optional.empty();
    return find(orgId, id);
  }

  @Override
  public boolean delete(long orgId, long id) {
    pinOrgContextForRls();
    return jdbc.update("DELETE FROM alert_destinations WHERE org_id = ? AND id = ?", orgId, id) > 0;
  }

  private void pinOrgContextForRls() {
    long orgId = OrgContext.requireCurrent();
    jdbc.queryForObject(
        "SELECT set_config('arguslog.org_id', ?, true)", String.class, String.valueOf(orgId));
  }

  private AlertDestination mapRow(ResultSet rs, int rowNum) throws SQLException {
    byte[] encrypted = rs.getBytes("config_encrypted");
    String configJson = new String(cipher.decrypt(encrypted), StandardCharsets.UTF_8);
    return new AlertDestination(
        rs.getLong("id"),
        rs.getLong("org_id"),
        DestinationKind.fromString(rs.getString("kind")),
        rs.getString("name"),
        configJson,
        rs.getTimestamp("created_at").toInstant());
  }
}
