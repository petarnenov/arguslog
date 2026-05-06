-- =====================================================================
-- Personal access tokens (PATs) — bearer credential the CLI uses to
-- talk to the api on behalf of a user. Format on the wire:
--   arglog_pat_<8-char-prefix>_<48-char-secret>
--
-- Storage:
--   prefix      — kept plaintext so the auth filter can do an O(1) row
--                 lookup before paying the argon2 verify cost.
--   token_hash  — argon2id of the FULL token. Never log the plaintext.
--
-- No RLS — these are user-owned, not org-owned. The application layer
-- enforces "I can only see my own tokens" at the controller boundary.
-- =====================================================================
CREATE TABLE personal_access_tokens (
  id            BIGSERIAL PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  prefix        CHAR(8) NOT NULL UNIQUE,
  token_hash    TEXT NOT NULL,
  expires_at    TIMESTAMPTZ,
  last_used_at  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pat_user ON personal_access_tokens(user_id);
