package org.arguslog.api.billing.adapter.out.postgres;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.PlanPurchaseRepository;
import org.arguslog.api.billing.domain.BillingProvider;
import org.arguslog.api.billing.domain.PlanPurchase;
import org.arguslog.api.billing.domain.PlanTier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcPlanPurchaseRepository implements PlanPurchaseRepository {

  private static final String SELECT_COLUMNS =
      "id, org_id, provider::text AS provider, provider_reference, plan::text AS plan,"
          + " duration_months, amount_cents, currency, pay_currency, applied_at, expires_at";

  private final JdbcTemplate jdbc;

  public JdbcPlanPurchaseRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public PlanPurchase recordIfNew(
      long orgId,
      BillingProvider provider,
      String providerReference,
      PlanTier plan,
      int durationMonths,
      int amountCents,
      String currency,
      Optional<String> payCurrency,
      Instant expiresAt) {
    jdbc.update(
        connection -> {
          PreparedStatement ps =
              connection.prepareStatement(
                  "INSERT INTO plan_purchases ("
                      + " org_id, provider, provider_reference, plan, duration_months,"
                      + " amount_cents, currency, pay_currency, expires_at)"
                      + " VALUES (?, ?::billing_provider_t, ?, ?::org_plan, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (provider, provider_reference) DO NOTHING");
          ps.setLong(1, orgId);
          ps.setString(2, provider.dbValue());
          ps.setString(3, providerReference);
          ps.setString(4, plan.dbValue());
          ps.setInt(5, durationMonths);
          ps.setInt(6, amountCents);
          ps.setString(7, currency);
          if (payCurrency.isPresent()) {
            ps.setString(8, payCurrency.get());
          } else {
            ps.setNull(8, Types.VARCHAR);
          }
          ps.setTimestamp(9, Timestamp.from(expiresAt));
          return ps;
        });

    return jdbc.queryForObject(
        "SELECT " + SELECT_COLUMNS + " FROM plan_purchases"
            + " WHERE provider = ?::billing_provider_t AND provider_reference = ?",
        rowMapper(),
        provider.dbValue(),
        providerReference);
  }

  @Override
  public Optional<PlanPurchase> findLatestForOrg(long orgId) {
    try {
      PlanPurchase row =
          jdbc.queryForObject(
              "SELECT " + SELECT_COLUMNS + " FROM plan_purchases"
                  + " WHERE org_id = ? ORDER BY applied_at DESC LIMIT 1",
              rowMapper(),
              orgId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<PlanPurchase> listForOrg(long orgId) {
    return jdbc.query(
        "SELECT " + SELECT_COLUMNS + " FROM plan_purchases"
            + " WHERE org_id = ? ORDER BY applied_at DESC",
        rowMapper(),
        orgId);
  }

  @Override
  public List<PlanPurchase> findExpiringBetween(Instant from, Instant to) {
    return jdbc.query(
        "SELECT " + SELECT_COLUMNS + " FROM plan_purchases"
            + " WHERE expires_at >= ? AND expires_at < ? ORDER BY expires_at ASC",
        rowMapper(),
        Timestamp.from(from),
        Timestamp.from(to));
  }

  private static RowMapper<PlanPurchase> rowMapper() {
    return (ResultSet rs, int rowNum) ->
        new PlanPurchase(
            rs.getLong("id"),
            rs.getLong("org_id"),
            BillingProvider.fromDbValue(rs.getString("provider")),
            rs.getString("provider_reference"),
            PlanTier.fromDbValue(rs.getString("plan")),
            rs.getInt("duration_months"),
            rs.getInt("amount_cents"),
            rs.getString("currency"),
            Optional.ofNullable(rs.getString("pay_currency")),
            rs.getTimestamp("applied_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant());
  }
}
