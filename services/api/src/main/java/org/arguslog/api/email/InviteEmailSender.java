package org.arguslog.api.email;

/**
 * Best-effort outbound email port for org invitations. Implementations should never throw on
 * delivery failure — invitations are persisted in the database before this is called, so a
 * failed/missing email shouldn't fail the user-facing request. Log warnings, emit metrics.
 */
public interface InviteEmailSender {

  /** Notify {@code recipientEmail} that they've been added to {@code orgId}. */
  void send(String recipientEmail, long orgId);
}
