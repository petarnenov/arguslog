package org.arguslog.api.billing.adapter.out.postgres;

import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.CryptoEventLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCryptoEventLog implements CryptoEventLog {

  private final JdbcTemplate jdbc;

  public JdbcCryptoEventLog(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public boolean recordIfNew(String paymentId, String status) {
    int rowsInserted =
        jdbc.update(
            "INSERT INTO crypto_events (payment_id, payment_status) VALUES (?, ?)"
                + " ON CONFLICT (payment_id, payment_status) DO NOTHING",
            paymentId,
            status);
    return rowsInserted == 1;
  }
}
