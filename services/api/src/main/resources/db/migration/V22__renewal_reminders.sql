-- One row per (org, target expiry date, kind) reminder we've delivered. Prevents the daily
-- worker from re-sending T-14 / T-7 / T-1 reminders if it runs twice in the same day, or if a
-- plan_renews_at edit nudges the same expiry into a different bucket.
--
-- The {@code (org_id, target_date, kind)} composite is the natural key. We don't reference a
-- specific plan_purchase row because the reminder semantics are about the org's *current*
-- renewal date, which can shift if a new purchase extends it; we only care that we don't
-- spam the same kind for the same target date.

CREATE TABLE renewal_reminders_sent (
    org_id      BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    target_date DATE NOT NULL,
    kind        SMALLINT NOT NULL CHECK (kind IN (14, 7, 1)),
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (org_id, target_date, kind)
);
