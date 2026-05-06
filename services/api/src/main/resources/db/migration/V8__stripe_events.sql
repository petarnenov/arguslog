-- =====================================================================
-- stripe_events — idempotency for the Stripe webhook handler.
--
-- Stripe redelivers any event whose receiver returned non-2xx or timed
-- out, with the same event_id. Without dedup we'd re-apply state changes
-- (e.g. mutate plan → free twice on a slow handler).
--
-- The PK on event_id makes "INSERT ON CONFLICT DO NOTHING" the natural
-- "have we seen this?" check — atomic, single round-trip.
-- =====================================================================
CREATE TABLE stripe_events (
  event_id     TEXT PRIMARY KEY,
  event_type   TEXT NOT NULL,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
