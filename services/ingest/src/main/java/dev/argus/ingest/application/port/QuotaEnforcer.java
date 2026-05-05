package dev.argus.ingest.application.port;

/** Outbound port: per-project rate limit + monthly event quota check. */
public interface QuotaEnforcer {

  enum Decision {
    ALLOW,
    RATE_LIMITED,
    QUOTA_EXCEEDED
  }

  Decision tryConsume(long projectId);
}
