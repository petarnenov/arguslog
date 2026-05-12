-- OSS conversion Phase 2 — drop all SaaS-billing tables.
--
-- These tables fed the Stripe / NOWPayments / Lemon Squeezy checkout flows, which are
-- deleted in this same Phase 2 commit. Tier grants are recorded in the admin_audit_log so no
-- new replacement table is needed.
--
-- Before merging this migration the operator MUST have run scripts/archive-billing-tables.sh
-- to pg_dump these tables into R2 — the dump is the only record we'll keep for accounting /
-- audit purposes.

DROP TABLE IF EXISTS plan_purchases    CASCADE;
DROP TABLE IF EXISTS stripe_events     CASCADE;
DROP TABLE IF EXISTS crypto_invoices   CASCADE;
DROP TABLE IF EXISTS crypto_events     CASCADE;
DROP TABLE IF EXISTS renewal_reminders_sent CASCADE;
