-- =====================================================================
-- Repeatable migration: SDK platforms catalog (single source of truth).
--
-- Flyway re-runs this file whenever its checksum changes (i.e. whenever an
-- SDK is added/removed or a sdk_version bump is committed). The earlier
-- versioned migrations V14–V19 seeded the table; from now on, version bumps
-- and metadata edits land HERE — no new Vxx migration per release.
--
-- Update protocol when an SDK ships a new version:
--   1. Bump the version in the SDK's own manifest (package.json /
--      pyproject.toml / java-sdk Gradle release tag).
--   2. Edit the matching `sdk_version` literal below to match.
--   3. Open the PR. The PlatformsCatalogParityTest in services/api fails
--      loudly if the two ever drift, so step 2 cannot be silently skipped.
--
-- The UPSERT preserves the `enabled` column and `created_at` timestamp so
-- admin toggles ("hide platform X from the dropdown") survive redeploys.
-- =====================================================================

INSERT INTO platforms (slug, name, sdk_package, sdk_version, sort_order) VALUES
  ('javascript',   'JavaScript / Browser', '@arguslog/sdk-browser',      '1.1.3', 10),
  ('react',        'React',                '@arguslog/sdk-react',        '1.1.3', 20),
  ('angular',      'Angular',              '@arguslog/sdk-angular',      '1.0.4', 22),
  ('vue',          'Vue',                  '@arguslog/sdk-vue',          '1.0.4', 23),
  ('nextjs',       'Next.js',              '@arguslog/sdk-nextjs',       '1.0.4', 25),
  ('web3',         'Web3 (EVM + Solana)',  '@arguslog/sdk-web3',         '0.3.0', 27),
  ('react-native', 'React Native',         '@arguslog/sdk-react-native', '1.0.3', 30),
  ('node',         'Node.js',              '@arguslog/sdk-node',         '1.0.2', 35),
  ('java-spring',  'Java / Spring Boot',   'org.arguslog:java-sdk',      '1.0.2', 40),
  ('python',       'Python',               'arguslog',                   '1.0.2', 50)
ON CONFLICT (slug) DO UPDATE SET
  name        = EXCLUDED.name,
  sdk_package = EXCLUDED.sdk_package,
  sdk_version = EXCLUDED.sdk_version,
  sort_order  = EXCLUDED.sort_order,
  updated_at  = NOW();
