package org.arguslog.api.billing.adapter.out.postgres;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.CryptoInvoiceRepository;
import org.arguslog.api.billing.domain.CryptoInvoice;
import org.arguslog.api.billing.domain.CryptoInvoiceStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcCryptoInvoiceRepository implements CryptoInvoiceRepository {

  private static final String SELECT_COLUMNS =
      "id, org_id, internal_reference, np_invoice_id, np_payment_id, duration_months,"
          + " price_amount_cents, price_currency, pay_amount, pay_currency, status::text AS status,"
          + " checkout_url, expires_at, created_at, updated_at";

  private final JdbcTemplate jdbc;

  public JdbcCryptoInvoiceRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public CryptoInvoice insertPending(long orgId, int durationMonths, int priceAmountCents) {
    UUID internalReference = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO crypto_invoices (org_id, internal_reference, duration_months, price_amount_cents)"
            + " VALUES (?, ?, ?, ?)",
        orgId,
        internalReference,
        durationMonths,
        priceAmountCents);
    return findByInternalReference(internalReference)
        .orElseThrow(() -> new IllegalStateException("crypto invoice insert race"));
  }

  @Override
  public Optional<CryptoInvoice> findByInternalReference(UUID internalReference) {
    return queryOne(
        "SELECT " + SELECT_COLUMNS + " FROM crypto_invoices WHERE internal_reference = ?",
        internalReference);
  }

  @Override
  public Optional<CryptoInvoice> findByNpInvoiceId(String npInvoiceId) {
    return queryOne(
        "SELECT " + SELECT_COLUMNS + " FROM crypto_invoices WHERE np_invoice_id = ?", npInvoiceId);
  }

  @Override
  public Optional<CryptoInvoice> findByNpPaymentId(String npPaymentId) {
    return queryOne(
        "SELECT " + SELECT_COLUMNS + " FROM crypto_invoices WHERE np_payment_id = ?", npPaymentId);
  }

  @Override
  public void attachNpInvoice(UUID internalReference, String npInvoiceId, String checkoutUrl) {
    jdbc.update(
        "UPDATE crypto_invoices SET np_invoice_id = ?, checkout_url = ?, updated_at = NOW()"
            + " WHERE internal_reference = ?",
        npInvoiceId,
        checkoutUrl,
        internalReference);
  }

  @Override
  public void applyIpnUpdate(
      long invoiceId,
      String npPaymentId,
      CryptoInvoiceStatus status,
      Optional<BigDecimal> payAmount,
      Optional<String> payCurrency,
      Optional<Instant> expiresAt,
      String rawPayloadJson) {
    jdbc.update(
        connection -> {
          PreparedStatement ps =
              connection.prepareStatement(
                  "UPDATE crypto_invoices SET"
                      + " np_payment_id = COALESCE(np_payment_id, ?),"
                      + " status = ?::crypto_invoice_status,"
                      + " pay_amount = COALESCE(?, pay_amount),"
                      + " pay_currency = COALESCE(?, pay_currency),"
                      + " expires_at = COALESCE(?, expires_at),"
                      + " last_ipn_payload = ?::jsonb,"
                      + " updated_at = NOW()"
                      + " WHERE id = ?");
          ps.setString(1, npPaymentId);
          ps.setString(2, status.dbValue());
          if (payAmount.isPresent()) {
            ps.setBigDecimal(3, payAmount.get());
          } else {
            ps.setNull(3, Types.NUMERIC);
          }
          if (payCurrency.isPresent()) {
            ps.setString(4, payCurrency.get());
          } else {
            ps.setNull(4, Types.VARCHAR);
          }
          if (expiresAt.isPresent()) {
            ps.setTimestamp(5, Timestamp.from(expiresAt.get()));
          } else {
            ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
          }
          ps.setString(6, rawPayloadJson);
          ps.setLong(7, invoiceId);
          return ps;
        });
  }

  private Optional<CryptoInvoice> queryOne(String sql, Object... args) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(sql, rowMapper(), args));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private static RowMapper<CryptoInvoice> rowMapper() {
    return (ResultSet rs, int rowNum) ->
        new CryptoInvoice(
            rs.getLong("id"),
            rs.getLong("org_id"),
            (UUID) rs.getObject("internal_reference"),
            Optional.ofNullable(rs.getString("np_invoice_id")),
            Optional.ofNullable(rs.getString("np_payment_id")),
            rs.getInt("duration_months"),
            rs.getInt("price_amount_cents"),
            rs.getString("price_currency"),
            Optional.ofNullable(rs.getBigDecimal("pay_amount")),
            Optional.ofNullable(rs.getString("pay_currency")),
            CryptoInvoiceStatus.fromDbValue(rs.getString("status")),
            Optional.ofNullable(rs.getString("checkout_url")),
            Optional.ofNullable(rs.getTimestamp("expires_at")).map(Timestamp::toInstant),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
  }
}
