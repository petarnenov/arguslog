// k6 — ingest hot path baseline.
//
// Measures the steady-state throughput of POST /api/{projectId}/events against the staging
// ingest service. The seed script (infra/k6/seed.sql) bootstraps a project + DSN that this
// script reads from env vars; nothing in the schema is mutated by this run beyond the events
// table itself.
//
// Run:
//   ARGUS_INGEST_URL=https://arguslog-ingest-staging.up.railway.app \
//   ARGUS_PROJECT_ID=101 \
//   ARGUS_DSN=k6_bench_pk_01HXYZK6BENCHPUBLIC0001 \
//   k6 run infra/k6/ingest-hot-path.js
//
// Default scenario ramps to 500 RPS sustained for 1 minute. Adjust via VUS / RATE / DURATION
// env vars.
import http from 'k6/http';
import { check } from 'k6';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const INGEST_URL = __ENV.ARGUS_INGEST_URL;
const PROJECT_ID = __ENV.ARGUS_PROJECT_ID || '101';
const DSN = __ENV.ARGUS_DSN;
// 60 tokens / 10 s = 6 RPS sustained per project (Bucket4jBurstLimiter is hardcoded). Default
// RATE stays a hair below that ceiling — anything higher is testing the rate limiter not the
// happy path. Override with RATE=N for protocol-level stress runs.
const VUS = parseInt(__ENV.VUS || '5', 10);
const RATE = parseInt(__ENV.RATE || '5', 10); // requests / second
const DURATION = __ENV.DURATION || '1m';

if (!INGEST_URL) throw new Error('ARGUS_INGEST_URL is required');
if (!DSN) throw new Error('ARGUS_DSN is required');

export const options = {
  scenarios: {
    hot_path: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: VUS * 4,
    },
  },
  thresholds: {
    // Target — the README explains why these numbers; failing one fails the run, which is what
    // we want for a regression baseline.
    http_req_failed: ['rate<0.05'], // < 5% errors
    // Targets capture p95/p99 measured against staging from EU → us-west1. Round-trip floor is
    // ~180 ms; anything past p99 = 500 ms is the app actually doing real work on top.
    http_req_duration: ['p(99)<500', 'p(95)<250'],
  },
};

const HEADERS = {
  'Content-Type': 'application/json',
  'X-Arguslog-Auth': `Arguslog DSN ${DSN}`,
};

export default function () {
  // Vary the message so fingerprinting paths are exercised — a single fingerprint test would
  // hammer the same issue row and miss lock contention on issue creation.
  const payload = JSON.stringify({
    level: 'error',
    message: `k6 hot path ${randomString(8)}`,
    timestamp: new Date().toISOString(),
    tags: { source: 'k6-hot-path' },
  });

  const res = http.post(`${INGEST_URL}/api/${PROJECT_ID}/events`, payload, { headers: HEADERS });

  check(res, {
    'status 202': (r) => r.status === 202,
    'has eventId': (r) => r.json('eventId') !== undefined,
  });
}
