# P2 — Dashboard core

> Goal (per project memory): "api + web + Keycloak" wired so a real user can
> log in, see their projects, and browse issues that the worker is producing.
>
> Definition of done: a user authenticated via Keycloak can hit
> `https://app.argus.local`, see their project's issue list, drill into one
> issue, and see its event stream — all reading from real Postgres data via
> the `api` service.

## Milestones

| #   | Milestone                                                                                                            | Status  | Commit    |
| --- | -------------------------------------------------------------------------------------------------------------------- | ------- | --------- |
| 1   | API: `/api/v1/projects/{id}/issues` paginated list — JOOQ-free JdbcTemplate, RLS-aware, real schema.                 | ✅ done | `ef0950b` |
| 2   | API auth glue — Keycloak JWT → user → org membership → `current_setting('argus.org_id')` set per request via filter. | ✅ done | `6f117b7` |
| 3   | API: issue detail + recent events endpoints; surface fingerprint, occurrence_count, level, last_seen.                | ✅ done | `b10b084` |
| 4   | Web login flow — `oidc-client-ts` redirect + PKCE callback against Keycloak realm, persisted via Zustand store.      | ✅ done | `86bdd66` |
| 5   | Web IssuesPage wired to real API via TanStack Query + Mantine table; status / level filters; pagination.             | ✅ done | `6cca436` |
| 6   | Web IssueDetailPage — title / culprit / chart from `issue_stats_5m`, recent events panel.                            | ✅ done | `ad1cd26` |
| 7   | Keycloak realm verification — confirm `argus-api` + `argus-web` clients, pwa scopes, default test users.             | ✅ done | `2b7c814` |
| 8   | OpenAPI artifact emit + the openapi-diff CI job lights up (was a placeholder under PR workflow).                     | ⏳ next | —         |

## Architecture decisions to lock in

- **API style:** REST under `/api/v1`, Mantine-friendly response envelopes
  `{data, page: {next, total?}}`. Errors use RFC 9457 (problem+json).
- **Pagination:** cursor-based (`?cursor=<opaque>&limit=50`), capped at 200
  per page. `last_seen_at desc, id desc` is the canonical issue ordering.
- **Tenancy:** the API filter sets `current_setting('argus.org_id')` per
  request inside a transaction; RLS policies (already in V1) enforce. No
  service-role bypass in the api process.
- **Frontend state:** TanStack Query owns server state; Zustand only holds
  auth/session. No Redux.
- **i18n:** the existing i18next setup; English first, Bulgarian copy in
  same PRs as the feature.
- **Routing:** React Router v7 with file-style route modules under
  `apps/web/src/router.tsx` (already there as scaffolding).

## Carry-forwards from P1

- The `out/` `.gitignore` lesson — never use unanchored `out/`, `port/`, or
  similar package-shaped patterns.
- One Flyway owner (api). Tests in api use `classpath:db/migration`; tests
  in other services point Flyway at `filesystem:` against api's source via
  `resolveMigrationsLocation()` (see ingest + worker tests).
- Smoke tests (`@SpringBootTest`) stay infra-free via `@MockitoBean` on
  every adapter port + autoconfig exclusions for DataSource / Redis.
- Integration tests use Testcontainers TimescaleDB (`timescale/timescaledb:
latest-pg16`) and migrate against api's source tree.
- Pact pattern: consumer regenerates JSON, CI diffs against committed
  copy. Same pattern would apply if web ↔ api gets a Pact suite.

## Out of scope for P2 (revisit later)

- Sourcemap upload + symbolication (P3).
- Alert dispatch (P3).
- Bucket4j real quotas (P4).
- SDK packages other than sdk-browser + java-sdk (v1.1).
- Admin panel for cross-org observability (P3 or P4).
