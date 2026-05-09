package org.arguslog.api.billing.application;

public interface NowPaymentsWebhookUseCase {

  Outcome handle(String rawJsonBody);

  enum Outcome {
    PROCESSED,
    ALREADY_SEEN,
    UNKNOWN_INVOICE,
    IGNORED
  }
}
