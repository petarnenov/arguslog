-- Track which tier the crypto invoice is buying so the IPN handler can apply the right plan.
-- Existing pre-tier-split rows default to 'pro' (the only paid tier before this change) so a
-- pending invoice mid-deploy still resolves to the correct tier on completion.

ALTER TABLE crypto_invoices
    ADD COLUMN plan org_plan NOT NULL DEFAULT 'pro';
