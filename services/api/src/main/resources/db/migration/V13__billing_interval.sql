-- P6 #6 — annual prepay support.
--
-- We track the billing interval on the org row so the dashboard can render the renewal cadence
-- ("renews every 12 months on …") without round-tripping to Stripe on each page load. The Stripe
-- subscription is the source of truth; this column is a denormalised cache populated from
-- checkout.session.completed and refreshed on customer.subscription.updated.
--
-- Default monthly so every existing org keeps its current cadence on the migration boundary.

CREATE TYPE billing_interval_t AS ENUM ('monthly', 'annual');

ALTER TABLE organizations
    ADD COLUMN billing_interval billing_interval_t NOT NULL DEFAULT 'monthly';
