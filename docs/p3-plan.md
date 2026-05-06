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
| 10  | Worker: symbolication — for JS events, fetch matching sourcemap from R2, decode top frames before persist.  | pending | —         |
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

## Out of scope for P3 (revisit later)

- Real KMS / Cloudflare Workers Secrets integration (P4 billing setup landing zone).
- Bucket4j real per-project quota throttling (still P4).
- Per-environment routing of alerts.
- Alert noise reduction beyond simple cooldown (anomaly detection, etc.).
- Cross-frame source maps (chained sourcemap → sourcemap → original).
