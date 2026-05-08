-- =====================================================================
-- Backfill: register @arguslog/sdk-angular in the platforms catalog.
-- The SDK shipped in V14's wave but was never seeded, so the project-
-- create dropdown and the marketing landing page have been silently
-- omitting Angular from their lists. This row puts it next to React
-- and React Native by sort_order.
-- =====================================================================
INSERT INTO platforms (slug, name, sdk_package, sdk_version, sort_order) VALUES
  ('angular', 'Angular', '@arguslog/sdk-angular', '1.0.0', 22);
