-- =====================================================================
-- Catalog of supported SDK platforms. Driving the project-create dropdown
-- (and the server-side validator) from a DB table instead of a hardcoded
-- enum lets us add a new SDK without an api+web release pair.
--
-- The slug is what gets stored in projects.platform — same wire format we
-- shipped before, so existing rows stay valid.
-- =====================================================================
CREATE TABLE platforms (
  slug          TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  sdk_package   TEXT,
  sdk_version   TEXT,
  enabled       BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order    INTEGER NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed the four currently-shipped SDKs in the order the dashboard used to
-- display them. Versions match what's published on npm + Maven Central as
-- of this migration; bump via SQL UPDATE when a new release goes out.
INSERT INTO platforms (slug, name, sdk_package, sdk_version, sort_order) VALUES
  ('javascript',   'JavaScript / Browser', '@arguslog/sdk-browser',      '1.0.0', 10),
  ('react',        'React',                '@arguslog/sdk-react',        '1.0.0', 20),
  ('react-native', 'React Native',         '@arguslog/sdk-react-native', '1.0.0', 30),
  ('java-spring',  'Java / Spring Boot',   'org.arguslog:java-sdk',      '1.0.0', 40);
