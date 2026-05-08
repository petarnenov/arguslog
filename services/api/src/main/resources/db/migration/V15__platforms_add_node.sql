-- =====================================================================
-- Register @arguslog/sdk-node in the platforms catalog so the dropdown
-- on the project-create form (and the landing page's SDK section) lists
-- it alongside the four SDKs seeded in V14.
-- =====================================================================
INSERT INTO platforms (slug, name, sdk_package, sdk_version, sort_order) VALUES
  ('node', 'Node.js', '@arguslog/sdk-node', '0.1.0', 35);
