-- =====================================================================
-- Register @arguslog/sdk-nextjs in the platforms catalog so the project
-- create dropdown lists it alongside the other shipped SDKs (V14, V15).
-- =====================================================================
INSERT INTO platforms (slug, name, sdk_package, sdk_version, sort_order) VALUES
  ('nextjs', 'Next.js', '@arguslog/sdk-nextjs', '1.0.0', 25);
