-- Mirror the V6 invariant on the PAT table (added in V5): a users.id rotation
-- triggered by a Keycloak realm reseed should propagate to bearer tokens too,
-- not blow up upsertFromJwt with a FK violation on personal_access_tokens.
ALTER TABLE personal_access_tokens
  DROP CONSTRAINT personal_access_tokens_user_id_fkey,
  ADD  CONSTRAINT personal_access_tokens_user_id_fkey
       FOREIGN KEY (user_id) REFERENCES users(id)
       ON UPDATE CASCADE ON DELETE CASCADE;
