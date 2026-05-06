-- TimescaleDB native retention policy: drop chunks older than the longest plan retention
-- (Enterprise = 365 days). Chunks are dropped at the partition boundary, so this is O(1) vs
-- a row-by-row DELETE — exactly what hypertables exist for.
--
-- The per-org retention for Free/Pro (30 days, plus any per-org override below 365) is enforced
-- by a separate worker job that runs nightly; chunk drop is the floor that catches Enterprise +
-- guarantees we never bleed disk past the longest plan.
SELECT add_retention_policy('events', INTERVAL '365 days');
