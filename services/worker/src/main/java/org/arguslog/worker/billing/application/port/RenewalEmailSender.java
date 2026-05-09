package org.arguslog.worker.billing.application.port;

import java.time.LocalDate;

public interface RenewalEmailSender {

  /** Returns {@code true} on a successful Resend 2xx, {@code false} on log-and-drop or transport failure. */
  boolean send(
      String recipientEmail, String orgName, String orgSlug, LocalDate expiresAt, int daysAhead);
}
