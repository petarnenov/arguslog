# P1 — Core ingest

> Goal (per project memory): "ingest + worker + minimal SDKs all wired"
>
> Definition of done: a real DSN created via the api can be used by an SDK to
> POST an event that survives ingest → Redis → worker → Postgres and is
> queryable as an issue.

## Milestones

| #   | Milestone                                                                                                      | Status   | Commit                                       |
| --- | -------------------------------------------------------------------------------------------------------------- | -------- | -------------------------------------------- |
| 1   | Postgres schema + Flyway, owned by api. Orgs / projects / dsns / events (Timescale hypertable) / issues / RLS. | ✅ done  | `32832f7`                                    |
| 2   | Real `ProjectAuthenticator` — Postgres lookup of `project_keys`. Replaces `StubProjectAuthenticator`.          | ✅ done  | `81fdf33`                                    |
| 3   | Worker implementation — Redis Streams consumer, fingerprint, UPSERT issue, INSERT event, ACK.                  | ✅ done  | `b6c67fb` (persistence) + listener follow-up |
| 4   | Java end-to-end — ingest → Redis → worker → Postgres in one JVM (real Testcontainers).                         | ✅ done  | (next commit)                                |
| 4b  | SDK ↔ ingest contract (Pact) — covers the HTTP wire format for sdk-browser / java-sdk.                         | pending  | — _(deferred to P1 follow-up or P2)_         |
| 5   | Real `QuotaEnforcer` (Bucket4j-on-Redis). **Deferred to P4** per architecture memory.                          | deferred | —                                            |

## Carry-forwards (read before touching adjacent code)

- **`.gitignore` `out/` lesson** — originally global `out/` swallowed every
  hexagonal `adapter/out/...` package, which was the real cause of the
  early `NoSuchBeanDefinitionException` (the "mock all 3 ports" patch in
  `932bc46` was a workaround, not a fix). Pinned to `/out/` in `81fdf33`.
  Watch for the same trap if anyone adds `in/`, `port/`, or other
  package-shaped ignore rules.
- **Migrations have one owner.** All Flyway SQL lives under
  `services/api/src/main/resources/db/migration/`. Other services run with
  `spring.flyway.enabled=false`. Tests in non-api services point Flyway
  at api's source tree via a `filesystem:` location resolver (see
  `PostgresProjectAuthenticatorTest.resolveMigrationsLocation`).
- **`IngestApplicationTests` mocks all 3 ports on purpose.** It is a
  context-loads smoke test, not an integration test. Do not wire a real
  `DataSource` / Redis there — keep the `DataSourceAutoConfiguration` +
  `RedisAutoConfiguration` exclusions in place.
- **DSN auth contract (current).** `X-Argus-Auth: Argus DSN <publicKey>`.
  Public-key-only; `dsn_secret_hash IS NULL` enforced. Secret-bearing
  DSNs (for backend SDKs) come in a follow-up: extend `Command` with
  `dsnSecret`, verify with argon2 against `dsn_secret_hash`.
- **Project ID source.** Currently passed in URL path
  (`POST /api/{projectId}/events`) and must match the DSN's `project_id`
  in `project_keys`. This double-check defends against DSN-leak-with-
  wrong-tenant attacks.

## Out of scope for P1 (revisit later)

- Source map symbolication (P3).
- Alerts / destinations (P3).
- Bucket4j rate limiting (P4) — `AllowAllQuotaEnforcer` stays.
- Sourcemap upload via `@argus/cli` (P3).
- Caffeine cache in front of `PostgresProjectAuthenticator` (P4 perf pass).
- Continuous aggregate `issue_stats_5m` is created by V1 migration but
  unused until the dashboard hits it (P2).
