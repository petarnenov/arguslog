-- =====================================================================
-- Backfill: register @arguslog/sdk-vue in the platforms catalog so the
-- project-create dropdown and the marketing landing page list it next
-- to the other JS-runtime SDKs (V14-V18). Sort order slots Vue between
-- Angular (22) and Node (35) so the Browser → React → Next → Angular →
-- Vue ordering stays grouped.
-- =====================================================================
INSERT INTO platforms (slug, name, sdk_package, sdk_version, sort_order) VALUES
  ('vue', 'Vue', '@arguslog/sdk-vue', '1.0.0', 23);
