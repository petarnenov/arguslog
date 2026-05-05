-- Argus initial schema. Owned by argus-api service.
-- TimescaleDB-backed multi-tenant error tracking schema.

CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

-- =====================================================================
-- Users mirror — Keycloak is the source of truth; we keep an FK target.
-- =====================================================================
CREATE TABLE users (
  id              UUID PRIMARY KEY,
  email           CITEXT NOT NULL UNIQUE,
  display_name    TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_seen_at    TIMESTAMPTZ
);

-- =====================================================================
-- Organizations & membership
-- =====================================================================
CREATE TYPE org_plan AS ENUM ('free', 'pro', 'enterprise');
CREATE TYPE org_role AS ENUM ('owner', 'admin', 'member');

CREATE TABLE organizations (
  id                       BIGSERIAL PRIMARY KEY,
  slug                     CITEXT NOT NULL UNIQUE,
  name                     TEXT NOT NULL,
  plan                     org_plan NOT NULL DEFAULT 'free',
  plan_renews_at           TIMESTAMPTZ,
  retention_days_override  INTEGER,
  stripe_customer_id       TEXT,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE org_members (
  org_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role       org_role NOT NULL,
  added_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (org_id, user_id)
);
CREATE INDEX idx_org_members_user ON org_members(user_id);

-- =====================================================================
-- Projects, keys, environments, members
-- =====================================================================
CREATE TYPE project_role AS ENUM ('admin', 'member', 'viewer');

CREATE TABLE projects (
  id           BIGSERIAL PRIMARY KEY,
  org_id       BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  slug         CITEXT NOT NULL,
  name         TEXT NOT NULL,
  platform     TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (org_id, slug)
);
CREATE INDEX idx_projects_org ON projects(org_id);

CREATE TABLE project_keys (
  id                 BIGSERIAL PRIMARY KEY,
  project_id         BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  dsn_public         TEXT NOT NULL UNIQUE,
  dsn_secret_hash    TEXT,
  active             BOOLEAN NOT NULL DEFAULT TRUE,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  rotated_at         TIMESTAMPTZ
);
CREATE INDEX idx_project_keys_project ON project_keys(project_id) WHERE active;

CREATE TABLE environments (
  id           BIGSERIAL PRIMARY KEY,
  project_id   BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  UNIQUE (project_id, name)
);

CREATE TABLE project_members (
  project_id   BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role         project_role NOT NULL,
  added_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (project_id, user_id)
);

-- =====================================================================
-- Releases & source maps
-- =====================================================================
CREATE TABLE releases (
  id           BIGSERIAL PRIMARY KEY,
  project_id   BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  version      TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (project_id, version)
);
CREATE INDEX idx_releases_project ON releases(project_id);

CREATE TABLE source_map_artifacts (
  id              BIGSERIAL PRIMARY KEY,
  release_id      BIGINT NOT NULL REFERENCES releases(id) ON DELETE CASCADE,
  r2_key          TEXT NOT NULL,
  original_path   TEXT NOT NULL,
  sha256          TEXT NOT NULL,
  size_bytes      BIGINT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sourcemaps_release_path ON source_map_artifacts(release_id, original_path);

-- =====================================================================
-- Issues & events
-- =====================================================================
CREATE TYPE issue_status AS ENUM ('unresolved', 'resolved', 'ignored');
CREATE TYPE event_level AS ENUM ('fatal', 'error', 'warning', 'info', 'debug');

CREATE TABLE issues (
  id                 BIGSERIAL PRIMARY KEY,
  project_id         BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  environment_id     BIGINT REFERENCES environments(id) ON DELETE SET NULL,
  fingerprint        TEXT NOT NULL,
  status             issue_status NOT NULL DEFAULT 'unresolved',
  level              event_level NOT NULL DEFAULT 'error',
  title              TEXT NOT NULL,
  culprit            TEXT,
  first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  occurrence_count   BIGINT NOT NULL DEFAULT 0,
  UNIQUE (project_id, environment_id, fingerprint)
);
CREATE INDEX idx_issues_project_lastseen ON issues(project_id, last_seen_at DESC);
CREATE INDEX idx_issues_project_status ON issues(project_id, status, last_seen_at DESC);

-- Hypertable: events.
-- Composite primary key includes received_at because TimescaleDB requires partitioning column
-- to be part of any unique index.
CREATE TABLE events (
  id              UUID NOT NULL,
  issue_id        BIGINT NOT NULL,
  project_id      BIGINT NOT NULL,
  environment_id  BIGINT,
  received_at     TIMESTAMPTZ NOT NULL,
  payload         JSONB NOT NULL,
  PRIMARY KEY (id, received_at)
);
SELECT create_hypertable('events', 'received_at', chunk_time_interval => INTERVAL '1 day');
CREATE INDEX idx_events_issue_time ON events(issue_id, received_at DESC);
CREATE INDEX idx_events_project_time ON events(project_id, received_at DESC);
ALTER TABLE events SET (timescaledb.compress, timescaledb.compress_segmentby = 'project_id,issue_id');
SELECT add_compression_policy('events', INTERVAL '7 days');

-- Continuous aggregate: 5-minute issue counts for the dashboard chart.
CREATE MATERIALIZED VIEW issue_stats_5m
WITH (timescaledb.continuous) AS
SELECT
  issue_id,
  project_id,
  time_bucket(INTERVAL '5 minutes', received_at) AS bucket,
  COUNT(*)::BIGINT AS count
FROM events
GROUP BY issue_id, project_id, bucket
WITH NO DATA;

SELECT add_continuous_aggregate_policy('issue_stats_5m',
  start_offset => INTERVAL '7 days',
  end_offset   => INTERVAL '5 minutes',
  schedule_interval => INTERVAL '5 minutes');

-- =====================================================================
-- Alert rules & destinations
-- =====================================================================
CREATE TYPE destination_kind AS ENUM ('telegram', 'email', 'slack', 'webhook');

CREATE TABLE alert_destinations (
  id                BIGSERIAL PRIMARY KEY,
  org_id            BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  kind              destination_kind NOT NULL,
  name              TEXT NOT NULL,
  config_encrypted  BYTEA NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (org_id, name)
);

CREATE TABLE alert_rules (
  id                BIGSERIAL PRIMARY KEY,
  project_id        BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name              TEXT NOT NULL,
  conditions        JSONB NOT NULL,
  actions           JSONB NOT NULL,
  throttle_seconds  INTEGER NOT NULL DEFAULT 300,
  enabled           BOOLEAN NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_alert_rules_project ON alert_rules(project_id) WHERE enabled;

-- =====================================================================
-- Audit log (hypertable)
-- =====================================================================
CREATE TABLE audit_log (
  id          UUID NOT NULL DEFAULT gen_random_uuid(),
  org_id      BIGINT,
  actor_id    UUID,
  action      TEXT NOT NULL,
  target      TEXT,
  metadata    JSONB,
  ts          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (id, ts)
);
SELECT create_hypertable('audit_log', 'ts', chunk_time_interval => INTERVAL '7 days');
CREATE INDEX idx_audit_org_ts ON audit_log(org_id, ts DESC);

-- =====================================================================
-- Quotas (per org, per billing month)
-- =====================================================================
CREATE TABLE quotas (
  org_id            BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  period_start      DATE NOT NULL,
  events_count      BIGINT NOT NULL DEFAULT 0,
  attachment_bytes  BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (org_id, period_start)
);

-- =====================================================================
-- Project-scoped PII rules
-- =====================================================================
CREATE TABLE pii_rules (
  id           BIGSERIAL PRIMARY KEY,
  project_id   BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  pattern      TEXT NOT NULL,
  replacement  TEXT NOT NULL DEFAULT '[Filtered]',
  enabled      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pii_rules_project ON pii_rules(project_id) WHERE enabled;

-- =====================================================================
-- Defense-in-depth row-level security.
-- App code MUST set `argus.org_id` per request. Service-role connections
-- bypass RLS via BYPASSRLS on the role used by ingest/worker (granted out-of-band).
-- =====================================================================
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_destinations ENABLE ROW LEVEL SECURITY;
ALTER TABLE quotas ENABLE ROW LEVEL SECURITY;

CREATE POLICY projects_org_isolation ON projects
  USING (org_id = current_setting('argus.org_id', true)::BIGINT);
CREATE POLICY issues_org_isolation ON issues
  USING (project_id IN (
    SELECT id FROM projects WHERE org_id = current_setting('argus.org_id', true)::BIGINT
  ));
CREATE POLICY alert_rules_org_isolation ON alert_rules
  USING (project_id IN (
    SELECT id FROM projects WHERE org_id = current_setting('argus.org_id', true)::BIGINT
  ));
CREATE POLICY alert_destinations_org_isolation ON alert_destinations
  USING (org_id = current_setting('argus.org_id', true)::BIGINT);
CREATE POLICY quotas_org_isolation ON quotas
  USING (org_id = current_setting('argus.org_id', true)::BIGINT);
