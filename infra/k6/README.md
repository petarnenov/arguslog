# k6 baseline

Two scenarios live under `infra/k6/`:

| Script                | Target                                                | Why                                                               |
| --------------------- | ----------------------------------------------------- | ----------------------------------------------------------------- |
| `ingest-hot-path.js`  | `POST /api/{projectId}/events` on the ingest service  | Steady-state event throughput — the load-bearing customer path.   |
| `dashboard-read.js`   | `GET /actuator/health/readiness`, `GET /v3/api-docs`  | Spring read latency baseline — proxy for the BillingPage poll.    |

The ingest run requires a seeded project + DSN. The seed lives in `seed.sql` and was applied
once against staging (committed to the live timescaledb pod). Running it again is a no-op.

## Run against staging

```bash
# Hot path (events POST). Defaults to 500 RPS for 1 minute.
ARGUS_INGEST_URL=https://arguslog-ingest-staging.up.railway.app \
ARGUS_PROJECT_ID=101 \
ARGUS_DSN=k6_bench_pk_01HXYZK6BENCHPUBLIC0001 \
k6 run infra/k6/ingest-hot-path.js

# Dashboard reads.
ARGUS_API_URL=https://arguslog-api-staging.up.railway.app \
k6 run infra/k6/dashboard-read.js
```

Override the load profile with `RATE`, `VUS`, `DURATION` env vars — see each script's header.

## Targets

| Scenario           | Metric                  | Target           | Notes                                                                     |
| ------------------ | ----------------------- | ---------------- | ------------------------------------------------------------------------- |
| `ingest-hot-path`  | `http_req_duration p99` | < 1000 ms        | Single Spring instance on Railway free tier; cold start excluded.         |
| `ingest-hot-path`  | `http_req_failed`       | < 5%             | Beyond that we're hitting Bucket4j burst limit, not real overload.        |
| `dashboard-read`   | `http_req_duration p95` | < 400 ms (spec)  | Springdoc spec reflection is the heavier read.                            |
| `dashboard-read`   | `http_req_duration p95` | < 200 ms (health)| Trivial endpoint; anything slower means Tomcat queueing under contention. |

Numbers from the first baseline run live in `docs/p5-baseline.md` — re-run on every PR that
touches the ingest hot path or the api request pipeline and update if a regression sticks.

## When the run fails

- **All requests 401.** DSN drift — re-apply `seed.sql` and double-check the env var matches
  the row in `project_keys`.
- **All requests 429.** Bucket4j burst limit kicked in (60 events / 10s default); lower
  `RATE` or set `RATE=10` to stay under the burst.
- **Sustained 5xx.** Likely a real regression — pull api/ingest logs from Railway (`railway
  logs --service arguslog-ingest --deployment`) and bisect.
