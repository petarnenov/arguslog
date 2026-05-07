// k6 — dashboard read-path baseline.
//
// Measures GET /api/v1/orgs/{id}/usage + GET /api/v1/orgs (cross-page polling baseline) under
// concurrent user load. Auth is skipped — the BillingPage poll runs against a JWT-bearer tokens
// path that we don't have a non-Keycloak way to mint yet, so this script targets the
// happy-path actuator endpoints + a couple of read-only routes that the public-only ingest
// path uses.
//
// Run:
//   ARGUSLOG_API_URL=https://arguslog-api-staging.up.railway.app \
//   k6 run infra/k6/dashboard-read.js
//
// Once Keycloak is on staging (#6), an authed variant lands here to hit the BillingPage poll
// directly. Until then we benchmark the unauthenticated endpoints to detect throughput
// regressions in Spring's request handling.
import http from 'k6/http';
import { check } from 'k6';

const API_URL = __ENV.ARGUSLOG_API_URL;
const VUS = parseInt(__ENV.VUS || '50', 10);
const DURATION = __ENV.DURATION || '1m';

if (!API_URL) throw new Error('ARGUSLOG_API_URL is required');

export const options = {
  scenarios: {
    dashboard_load: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    // Targets are floor + headroom. Round-trip from EU to staging in us-west1 is ~180 ms,
    // so anything well past 250 ms means actual application work. Bump these once production
    // moves to a closer region.
    'http_req_duration{endpoint:health}': ['p(95)<250'],
    'http_req_duration{endpoint:openapi}': ['p(95)<400'],
  },
};

export default function () {
  const health = http.get(`${API_URL}/actuator/health/readiness`, {
    tags: { endpoint: 'health' },
  });
  check(health, { 'health 200': (r) => r.status === 200 });

  // OpenAPI spec is a heavier read (springdoc enumerates every controller) — gives a
  // more realistic "Spring is doing actual work" baseline than the trivial health endpoint.
  const spec = http.get(`${API_URL}/v3/api-docs`, {
    tags: { endpoint: 'openapi' },
  });
  check(spec, { 'openapi 200': (r) => r.status === 200 });
}
