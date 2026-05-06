package org.arguslog.api.billing.adapter.out.postgres;

import javax.sql.DataSource;
import org.arguslog.api.billing.application.port.StripeEventLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcStripeEventLog implements StripeEventLog {

  private final JdbcTemplate jdbc;

  public JdbcStripeEventLog(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public boolean recordIfNew(String eventId, String eventType) {
    int rowsInserted =
        jdbc.update(
            "INSERT INTO stripe_events (event_id, event_type) VALUES (?, ?)"
                + " ON CONFLICT (event_id) DO NOTHING",
            eventId,
            eventType);
    return rowsInserted == 1;
  }
}
