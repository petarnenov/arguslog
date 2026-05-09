-- NOWPayments crypto checkout state.
--
-- Two tables, mirroring the Stripe pair:
--   crypto_invoices  — one row per checkout we mint. Provider invoice id (np_invoice_id) lands
--                      after the NOWPayments REST call returns; the row is keyed on our internal
--                      reference so the IPN webhook can correlate before the API response races
--                      back to the user.
--   crypto_events    — webhook idempotency. NOWPayments redelivers IPNs on 5xx, the same way
--                      Stripe does — we dedupe on (payment_id, payment_status) tuple so the
--                      "waiting → confirming → finished" lifecycle each gets exactly-once
--                      processing.

CREATE TYPE crypto_invoice_status AS ENUM (
    'pending',
    'waiting',
    'confirming',
    'confirmed',
    'sending',
    'partially_paid',
    'finished',
    'failed',
    'refunded',
    'expired'
);

CREATE TABLE crypto_invoices (
    id                    BIGSERIAL PRIMARY KEY,
    org_id                BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    internal_reference    UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    np_invoice_id         TEXT,
    np_payment_id         TEXT,
    duration_months       INTEGER NOT NULL CHECK (duration_months IN (1, 3, 6, 12)),
    price_amount_cents    INTEGER NOT NULL CHECK (price_amount_cents > 0),
    price_currency        TEXT NOT NULL DEFAULT 'USD',
    pay_amount            NUMERIC(38, 18),
    pay_currency          TEXT,
    status                crypto_invoice_status NOT NULL DEFAULT 'pending',
    checkout_url          TEXT,
    expires_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_ipn_payload      JSONB
);

CREATE INDEX idx_crypto_invoices_org ON crypto_invoices (org_id, created_at DESC);
CREATE UNIQUE INDEX idx_crypto_invoices_np_invoice ON crypto_invoices (np_invoice_id)
    WHERE np_invoice_id IS NOT NULL;
CREATE UNIQUE INDEX idx_crypto_invoices_np_payment ON crypto_invoices (np_payment_id)
    WHERE np_payment_id IS NOT NULL;

CREATE TABLE crypto_events (
    payment_id     TEXT NOT NULL,
    payment_status TEXT NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (payment_id, payment_status)
);
