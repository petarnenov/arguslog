package org.arguslog.worker.adapter.out.postgres;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.arguslog.crypto.SecretCipher;
import org.arguslog.worker.application.port.AlertDestinationRepository;
import org.arguslog.worker.domain.AlertDestination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads {@code alert_destinations} rows by id and decrypts the per-destination config inline. Rows
 * that fail to decrypt (rotated key, corrupted ciphertext) are dropped with a warn — losing one
 * destination shouldn't block the rest of the fan-out.
 */
@Component
public class JdbcAlertDestinationRepository implements AlertDestinationRepository {

  private static final Logger log = LoggerFactory.getLogger(JdbcAlertDestinationRepository.class);

  private static final String SELECT_BY_IDS_SQL =
      """
      SELECT id, org_id, kind::text AS kind_text, name, config_encrypted
        FROM alert_destinations
       WHERE id IN (:ids)
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final SecretCipher cipher;

  public JdbcAlertDestinationRepository(DataSource dataSource, SecretCipher cipher) {
    this.jdbc = new NamedParameterJdbcTemplate(new JdbcTemplate(dataSource));
    this.cipher = cipher;
  }

  @Override
  public List<AlertDestination> findAllById(List<Long> ids) {
    if (ids == null || ids.isEmpty()) return List.of();
    MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
    Map<Long, AlertDestination> indexed = new HashMap<>();
    jdbc.query(
        SELECT_BY_IDS_SQL,
        params,
        rs -> {
          long id = rs.getLong("id");
          AlertDestination.Kind kind;
          try {
            kind =
                AlertDestination.Kind.valueOf(rs.getString("kind_text").toUpperCase(Locale.ROOT));
          } catch (IllegalArgumentException e) {
            log.warn("destination {} has unknown kind {}; skipping", id, rs.getString("kind_text"));
            return;
          }
          String configJson;
          try {
            byte[] decrypted = cipher.decrypt(rs.getBytes("config_encrypted"));
            configJson = new String(decrypted, StandardCharsets.UTF_8);
          } catch (RuntimeException e) {
            log.warn("destination {} ciphertext failed to decrypt: {}", id, e.getMessage());
            return;
          }
          indexed.put(
              id,
              new AlertDestination(
                  id, rs.getLong("org_id"), kind, rs.getString("name"), configJson));
        });
    // Preserve the input order so observers see deterministic dispatch ordering.
    List<AlertDestination> ordered = new java.util.ArrayList<>(ids.size());
    for (long id : ids) {
      AlertDestination d = indexed.get(id);
      if (d != null) ordered.add(d);
    }
    return ordered;
  }
}
