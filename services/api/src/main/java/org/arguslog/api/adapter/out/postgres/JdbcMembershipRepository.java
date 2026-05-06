package org.arguslog.api.adapter.out.postgres;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.application.port.MembershipRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMembershipRepository implements MembershipRepository {

  private final JdbcTemplate jdbc;

  public JdbcMembershipRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public boolean userIsMemberOfOrg(UUID userId, long orgId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM org_members WHERE user_id = ? AND org_id = ?)",
            Boolean.class,
            userId,
            orgId);
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public Optional<String> userRoleInOrg(UUID userId, long orgId) {
    try {
      String role =
          jdbc.queryForObject(
              "SELECT role::text FROM org_members WHERE user_id = ? AND org_id = ?",
              String.class,
              userId,
              orgId);
      return Optional.ofNullable(role);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
