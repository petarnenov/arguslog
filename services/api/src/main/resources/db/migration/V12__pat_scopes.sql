-- Granular PAT scopes. NULL means "all scopes" — preserves the implicit-all contract for any
-- token minted before this column existed. New tokens can pick a subset; the auth filter
-- promotes each scope into a Spring Security authority ("SCOPE_releases:write" etc.) and
-- @PreAuthorize on sensitive endpoints checks for them.
ALTER TABLE personal_access_tokens ADD COLUMN scopes TEXT[];
