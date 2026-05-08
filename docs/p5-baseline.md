# P5 — k6 baseline numbers

First baseline captured **2026-05-06** against the freshly-deployed staging environment
(`arguslog-*-staging.up.railway.app`, Railway us-west1, free tier). Runner was the developer
laptop on home broadband from EU — round-trip floor is **~180 ms**, so all latency targets
inherit that as a baked-in tax until production moves to a closer region.

## Ingest hot path — `infra/k6/ingest-hot-path.js`

Steady-state event ingest at the design ceiling (`Bucket4jBurstLimiter` is hardcoded to
60 tokens / 10 s = **6 RPS** sustained per project; the script defaults to 5 RPS to leave a
margin for burst). Run length: 30 s.

| Metric                  | Value     | Target       | Notes                                                               |
| ----------------------- | --------- | ------------ | ------------------------------------------------------------------- |
| Throughput              | 5.00 RPS  | _design cap_ | Bucket4j caps at 6 RPS sustained per project; runs at 5 to be safe. |
| Errors                  | 0.00 %    | < 5 %        | All 151 requests returned 202.                                      |
| `http_req_duration` avg | 199.43 ms | _floor 180_  | ~20 ms over the network floor — Spring + JDBC + Redis path.         |
| `http_req_duration` p95 | 217.18 ms | < 250 ms     | Variance dominated by TLS handshake + network jitter.               |
| `http_req_duration` p99 | 345.89 ms | < 500 ms     | Tail likely cold JIT + Hikari pool warmup; settles after ~30 s.     |
| `http_req_duration` max | 360.72 ms | _no target_  |                                                                     |

## Dashboard read — `infra/k6/dashboard-read.js`

Two read endpoints alternated at 50 VUs, no auth (Keycloak comes online in #6). Total
244 req/s split across health + openapi.

| Metric                                    | Value     | Target      | Notes                                                        |
| ----------------------------------------- | --------- | ----------- | ------------------------------------------------------------ |
| Throughput                                | 244 RPS   | _no target_ | Spring's actuator + springdoc both happy under 50 VUs.       |
| Errors                                    | 0.00 %    | < 5 %       |                                                              |
| `http_req_duration{endpoint:health}` p95  | 232.78 ms | < 250 ms    | Trivial endpoint — pure network floor + Tomcat scheduling.   |
| `http_req_duration{endpoint:openapi}` p95 | 264.23 ms | < 400 ms    | Springdoc walks every controller; ~30 ms of actual app work. |
| `http_req_duration{endpoint:openapi}` max | 951.22 ms | _no target_ | Single outlier ~5σ; investigate if pattern repeats next run. |

## How to compare against this baseline

The thresholds above live in the k6 scripts themselves; a regression fails the run, which
is what we want for any PR that touches the ingest pipeline or the api request path.

When the threshold genuinely needs to move (deploying to a closer region, bumping
Bucket4j capacity, etc.), update both the script's `thresholds` block AND this doc in the
same PR. Otherwise the regression detector slowly bit-rots into a rubber stamp.
