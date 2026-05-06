-- Allow users.id rotations to propagate to membership tables.
-- A Keycloak realm reseed gives the same user a new sub claim; upsertFromJwt
-- realigns users.id to the new sub, but with a plain ON DELETE CASCADE FK,
-- the UPDATE was blocked by the membership references and surfaced as a 500.
ALTER TABLE org_members
  DROP CONSTRAINT org_members_user_id_fkey,
  ADD  CONSTRAINT org_members_user_id_fkey
       FOREIGN KEY (user_id) REFERENCES users(id)
       ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE project_members
  DROP CONSTRAINT project_members_user_id_fkey,
  ADD  CONSTRAINT project_members_user_id_fkey
       FOREIGN KEY (user_id) REFERENCES users(id)
       ON UPDATE CASCADE ON DELETE CASCADE;
