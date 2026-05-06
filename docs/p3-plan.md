# P3 — Alerts + symbolication

> Goal (per project memory): "Alerts + symbolication".
>
> Definition of done:
>
> - A user can create an alert rule (e.g. "level=fatal in project X") and a
>   destination (Telegram chat / email / Slack webhook / generic webhook),
>   and the worker dispatches a message within seconds when a matching event
>   lands — with throttling so a flood doesn't spam.
> - The CLI uploads sourcemaps for a release; the worker enriches a JS event's
>   stack frames with the original source location before persisting.

## Milestone tracker

| #   | Milestone                                                                                                   | Status  | Commit    |
| --- | ----------------------------------------------------------------------------------------------------------- | ------- | --------- |
| 1   | API: `alert_destinations` CRUD (Telegram / Slack / email / webhook). Config is per-org encrypted at rest.   | ✅ done | `2af4e18` |
| 2   | API: `alert_rules` CRUD (project-scoped, JSONB conditions, throttle_seconds, enabled flag).                 | ✅ done | `f83851f` |
| 3   | Worker: rule evaluator — on issue persisted, find matching enabled rules, enqueue for dispatch.             | ✅ done | `5d81669` |
| 4a  | Worker: dispatch fan-out — Telegram first (smallest blast radius).                                          | ✅ done | `bb10cb3` |
| 4b  | Worker: dispatch fan-out — email (Resend) + Slack/webhook.                                                  | ✅ done | `e4c12fb` |
| 5   | Worker: throttling — Redis-backed `last_fired_at` per rule, skip if within `throttle_seconds`.              | ✅ done | `b7042d8` |
| 6   | Web: AlertRulesPage + AlertDestinationsPage under project / org settings.                                   | ✅ done | `509af8e` |
| 7a  | API: `releases` endpoint (CRUD, RLS) backing the CLI release-new command.                                   | ✅ done | `cbf6902` |
| 7b  | CLI: `arguslog releases new <version>` (PAT auth) — POST to /api/v1/projects/.../releases.                  | ✅ done | `3aa72f3` |
| 7c  | API: PAT auth — `personal_access_tokens` table + `/api/v1/me/tokens` + Spring Security PAT filter.          | ✅ done | `a313415` |
| 8   | CLI: `arguslog sourcemaps upload <release> <path>` — multipart upload to R2 via api signed URL.             | ✅ done | `3aa72f3` |
| 9   | API: signed-URL endpoint for sourcemap PUTs; persists to `source_map_artifacts`.                            | ✅ done | `22d598b` |
| 10  | Worker: symbolication — for JS events, fetch matching sourcemap from R2, decode top frames before persist.  | ✅ done | `11a86fc` |
| 11  | Web: surface symbolicated frames on IssueDetailPage; show a "raw" toggle for the minified version.          | pending | —         |

## Recommended starting order

**Track A — Alerts first (use existing schema, smaller blast radius):**
`#1 → #2 → #3 → #4 (Telegram) → #5 → rest of #4`

**Track B — Symbolication first (lights up the dashboard's killer demo):**
`#7 → #9 → #8 → #10 → #11`

The two tracks are independent until #6 / #11 (web). Either can ship first.

## Architecture decisions to lock in

- **Destination secrets:** stored in `alert_destinations.config_encrypted` (BYTEA). Encrypted with
  AES-256-GCM using a key derived from a per-org KMS material; key rotation handled by re-encrypting
  on read-fail. (Defer real KMS integration to P4 — for P3 use a single env-vared master key with
  a versioning prefix so rotation is just a config change.)
- **Rule conditions DSL (small, JSONB):**
  - `level: in ["fatal","error"]`
  - `tag: { key: "env", in: ["prod"] }`
  - `firstSeenWindow: PT5M` (only fire on issues new in the last N)
  - `occurrenceThreshold: 100` (only fire when occurrence_count crosses N)
  - All AND-ed; OR-grouping postponed.
- **Dispatch backpressure:** dispatch happens in the worker, on the same Redis Stream consumer as
  event ingestion, but on a separate consumer group (`worker-alerts`) so a slow Telegram doesn't
  block event persistence. New stream key: `events:persisted` (worker writes after a successful
  persist, alert consumer reads).
- **Throttling key:** `alert:throttle:{ruleId}` in Redis with TTL = `throttle_seconds`. SETNX check
  before fire.
- **Sourcemap storage:** path = `{orgId}/{projectId}/{releaseId}/{originalPath}.map`. R2 keys live
  in `source_map_artifacts.r2_key`.
- **Sourcemap fetch:** worker uses Caffeine + R2 client; LRU bounded to 256 maps to keep heap
  predictable. Cache miss → R2 GET via SDK presigned URL (or direct if same VPC).
- **CLI auth:** uses an Arguslog PAT (created via web app, stored in `~/.arguslog/credentials`). API
  exchanges PAT for a short-lived JWT for upload URL signing.

## Carry-forwards from P1 / P2

- One Flyway owner = api. Migrations for new alert tables already exist (V1); only behavior code
  needs writing.
- All app-layer routes go through `ProjectAccessGuard` + `OrgContext` + RLS pin, even alerts.
- New api endpoints emit OpenAPI fragments — `OpenApiContractTest` will catch shape changes.
- Tests follow the layered convention: pure unit + Testcontainers integration + (where it counts)
  contract via Pact / OpenAPI / realm-import snapshot.

## Resume here (next session)

**Where we stopped:** alerts track 100% done; symbolication api + CLI done; **#10 + #11 remain**.
The api side already mints presigned PUT URLs and the CLI already uploads to R2, so worker just
needs to read the bytes back, decode, and enrich event payloads before persist.

### #10 — worker symbolication (next up)

Reads `source_map_artifacts` from Postgres, fetches the `.map` from R2, decodes the top frames of
JS exception payloads, and writes the enriched payload back **before** `ProcessEventService` runs
fingerprint+persist (so a fingerprint over the original frame stays stable across re-uploads).

**Plan locked in this session (decisions to honor on resume):**

- **Trigger:** event payload carries `release: "<version>"` (SDK responsibility — out of scope for
  worker; if missing, symbolicator is a no-op).
- **Lookup:** `(projectId, release version)` → `release_id`, then `(release_id, originalPath)` →
  `r2_key`. Frame's `filename` is normalised by stripping the leading `/` and any cache-busting
  hash segment (CLI's `--name` flag is the canonical authority for what gets recorded as
  `originalPath`).
- **Fetch + cache:** Caffeine LRU bounded to 256 parsed maps, keyed by `r2_key`. Miss → S3
  `getObject` against the configured R2 endpoint (worker already has `aws.s3` dep; copy
  `R2Properties` + `R2Config` from api as `arguslog.r2.*` matches the existing config block).
- **VLQ + parser:** hand-rolled. Sourcemap v3 spec is small (~150 lines incl. `;`/`,` segment
  walk). No third-party sourcemap library — keeps the dep surface minimal.
- **Frame mutation:** add fields to each frame in place (don't drop the originals — the dashboard
  may want a "raw" toggle in #11):
    - `originalFilename`, `originalLineno`, `originalColno`, `originalFunction`
- **Hook point:** new `Symbolicator` port → `String symbolicate(long projectId, String rawPayload)`.
  `ProcessEventService.process` calls it BEFORE `fingerprinter.compute(...)` so the fingerprint
  reflects the symbolicated frames. No-op when `release` is missing or no artifact resolves.
- **Failure mode:** any error inside symbolicator (R2 down, malformed sourcemap, decode bug) is
  logged at warn and the original payload returns through unchanged. Symbolication failure must
  never drop the event.

**Files to add (estimate ~14 source + 5 test):**

| File | Purpose |
| --- | --- |
| `worker/build.gradle.kts` | add `caffeine` dep |
| `gradle/libs.versions.toml` | `caffeine = "com.github.ben-manes.caffeine:caffeine:3.1.8"` |
| `worker/application/port/Symbolicator.java` | port |
| `worker/application/CachingSymbolicator.java` | impl + Caffeine cache |
| `worker/application/port/SymbolicationRepository.java` | `findArtifact(projectId, version, originalPath)` |
| `worker/application/port/SourceMapStore.java` | fetch raw `.map` bytes by `r2_key` |
| `worker/domain/ParsedSourceMap.java` | parsed map + lookup `(line,col) → original` |
| `worker/adapter/out/sourcemap/SourceMapJsonParser.java` | JSON → ParsedSourceMap |
| `worker/adapter/out/sourcemap/Vlq.java` | base64-VLQ decode util |
| `worker/adapter/out/postgres/JdbcSymbolicationRepository.java` | `(projectId, version)` JOIN |
| `worker/adapter/out/r2/R2Properties.java` | copy from api (worker uses same `arguslog.r2.*`) |
| `worker/adapter/out/r2/R2Config.java` | S3Client only (no presigner needed worker-side) |
| `worker/adapter/out/r2/S3SourceMapStore.java` | adapter |
| `worker/application/ProcessEventService.java` | call symbolicator before fingerprint |

**Tests:**

- `VlqTest` — decode known fixtures from sourcemap v3 spec
- `SourceMapJsonParserTest` — small handcrafted `.map` fixture
- `ParsedSourceMapTest` — `(line,col) → (sourceFile,line,col,name)` lookup
- `CachingSymbolicatorTest` — mock store + repo, verify frame enrichment + cache hit on second call
- `JdbcSymbolicationRepositoryTest` — Testcontainers, two-hop join

Plus update `WorkerApplicationTests` smoke with `@MockitoBean SymbolicationRepository` +
`SourceMapStore`.

### #11 — web symbolicated frames (after #10)

Display the enriched frame fields on `IssueDetailPage` with a "raw" toggle that swaps in
`filename`/`lineno`/`colno` from the original frame. New api work needed: include the full
`payload.exception.values[*].stacktrace.frames` in the `IssueEvent` response (likely already there
— verify before touching api). Pure web work otherwise.

### Carry-forwards / open TODOs from earlier in P3

- **AesGcmSecretCipher duplication** (`api/.../crypto/` + `worker/.../crypto/`) — extract to a
  shared module in P4.
- **RLS owner-bypass in tests** — JdbcReleaseRepositoryTest etc. document this; resolve by
  splitting test container roles in P4.
- **PAT scopes** — currently single implicit `pat` scope. Per-resource scopes
  (`releases:write`, `sourcemaps:write`, …) deferred to P4.
- **Web tokens UI** — PAT api landed without a "Personal access tokens" page on the dashboard.
  Either add a minimal page early in P4 or surface tokens via the OnboardingPage so users can
  mint one without curl.

## Out of scope for P3 (revisit later)

- Real KMS / Cloudflare Workers Secrets integration (P4 billing setup landing zone).
- Bucket4j real per-project quota throttling (still P4).
- Per-environment routing of alerts.
- Alert noise reduction beyond simple cooldown (anomaly detection, etc.).
- Cross-frame source maps (chained sourcemap → sourcemap → original).
