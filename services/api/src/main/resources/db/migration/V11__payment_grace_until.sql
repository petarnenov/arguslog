-- Grace window started by `invoice.payment_failed`. NULL = no grace active. The webhook only
-- writes this column when no grace is open or the previous one already lapsed, so Stripe Smart
-- Retries (which fire repeated payment_failed events over ~4 weeks) cannot keep extending the
-- window past the first failure.
ALTER TABLE organizations ADD COLUMN payment_grace_until TIMESTAMPTZ;
