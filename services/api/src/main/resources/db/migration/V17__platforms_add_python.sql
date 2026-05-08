-- =====================================================================
-- Register the Python SDK in the platforms catalog so the project-create
-- dropdown lists it alongside the JS, Java, and Node options seeded in
-- V14/V15. The PyPI distribution name is just `arguslog` — it claims the
-- bare top-level name to match what users expect from `pip install`.
-- =====================================================================
INSERT INTO platforms (slug, name, sdk_package, sdk_version, sort_order) VALUES
  ('python', 'Python', 'arguslog', '1.0.0', 50);
