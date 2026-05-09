-- Provider abstraction + universal plan purchase audit log.
--
-- Why: Arguslog billing migrates from Stripe (recurring) to NOWPayments + Lemon Squeezy
-- (one-time). Plan purchases need a cross-provider audit trail that is the source of truth for
-- expiry, renewal reminders, and accounting export — independently of provider-specific tables
-- (stripe_events, crypto_invoices, lemon_squeezy_orders).
--
-- The existing billing_interval_t enum is extended with one-time durations so the same column
-- can describe both legacy Stripe rows ('monthly'/'annual') and new one-time purchases
-- ('one_month'/'three_months'/'six_months'/'twelve_months'). Old values are kept — Postgres
-- cannot drop enum values without a TYPE swap, and the Stripe code path stays compilable behind
-- a feature flag.

CREATE TYPE billing_provider_t AS ENUM ('stripe', 'nowpayments', 'lemon_squeezy');

CREATE TABLE plan_purchases (
    id                    BIGSERIAL PRIMARY KEY,
    org_id                BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    provider              billing_provider_t NOT NULL,
    provider_reference    TEXT NOT NULL,
    plan                  org_plan NOT NULL,
    duration_months       INTEGER NOT NULL CHECK (duration_months > 0 AND duration_months <= 36),
    amount_cents          INTEGER NOT NULL CHECK (amount_cents >= 0),
    currency              TEXT NOT NULL DEFAULT 'USD',
    pay_currency          TEXT,
    applied_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at            TIMESTAMPTZ NOT NULL,
    metadata              JSONB,
    UNIQUE (provider, provider_reference)
);

CREATE INDEX idx_plan_purchases_org_applied ON plan_purchases (org_id, applied_at DESC);
CREATE INDEX idx_plan_purchases_expires ON plan_purchases (expires_at);

ALTER TYPE billing_interval_t ADD VALUE IF NOT EXISTS 'one_month';
ALTER TYPE billing_interval_t ADD VALUE IF NOT EXISTS 'three_months';
ALTER TYPE billing_interval_t ADD VALUE IF NOT EXISTS 'six_months';
ALTER TYPE billing_interval_t ADD VALUE IF NOT EXISTS 'twelve_months';
