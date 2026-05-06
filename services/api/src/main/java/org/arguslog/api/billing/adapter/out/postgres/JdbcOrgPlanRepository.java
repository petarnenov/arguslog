package org.arguslog.api.billing.adapter.out.postgres;

import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.domain.PlanTier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrgPlanRepository implements OrgPlanRepository {

  private final JdbcTemplate jdbc;

  public JdbcOrgPlanRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Optional<PlanTier> findPlan(long orgId) {
    try {
      String raw =
          jdbc.queryForObject(
              "SELECT plan::text FROM organizations WHERE id = ?", String.class, orgId);
      return Optional.of(PlanTier.fromDbValue(raw));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }
}
