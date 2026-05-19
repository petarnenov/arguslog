/**
 * Pre-suite orphan-org sweep. The runner user is on the silver tier (3-org cap);
 * once a previous run's teardown was killed mid-flight (CI worker timeout, etc.)
 * the leftover `e2e-*` orgs accumulate and a fresh suite hits 402 PaymentRequired
 * on the very first `createOrg` call.
 *
 * Runs once before any spec via `globalSetup` in playwright.config.ts. Read the
 * runner's org list, filter to slugs starting with `e2e-`, DELETE each one. Best
 * effort — a delete failure does NOT abort the suite (we'd rather see the per-
 * spec 402 than mask it with a global setup error).
 */
import { e2eConfig } from '../playwright.config.js';

async function deleteE2eOrgs(): Promise<void> {
  const pat = e2eConfig.runnerPAT;
  if (!pat) {
    console.warn('[globalSetup] ARGUSLOG_E2E_RUNNER_PAT not set — skipping orphan sweep');
    return;
  }
  const headers = { Authorization: `Bearer ${pat}`, Accept: 'application/json' };
  let resp: Response;
  try {
    resp = await fetch(`${e2eConfig.apiURL}/api/v1/orgs`, { headers });
  } catch (err) {
    console.warn(`[globalSetup] orgs list fetch failed — skipping sweep:`, err);
    return;
  }
  if (!resp.ok) {
    console.warn(`[globalSetup] GET /api/v1/orgs → ${resp.status} — skipping sweep`);
    return;
  }
  const orgs = (await resp.json()) as { id: number; slug: string }[];
  const e2eOrgs = orgs.filter((o) => o.slug.startsWith('e2e-'));
  if (e2eOrgs.length === 0) {
    console.warn('[globalSetup] no orphan e2e-* orgs to sweep');
    return;
  }
  console.warn(
    `[globalSetup] sweeping ${e2eOrgs.length} orphan e2e-* org(s): ${e2eOrgs.map((o) => o.slug).join(', ')}`,
  );
  await Promise.all(
    e2eOrgs.map(async (o) => {
      try {
        const del = await fetch(`${e2eConfig.apiURL}/api/v1/orgs/${o.id}`, {
          method: 'DELETE',
          headers,
        });
        if (!del.ok && del.status !== 404) {
          console.warn(`[globalSetup] DELETE org ${o.slug} → ${del.status}`);
        }
      } catch (err) {
        console.warn(`[globalSetup] DELETE org ${o.slug} failed:`, err);
      }
    }),
  );
}

export default async function globalSetup(): Promise<void> {
  await deleteE2eOrgs();
}
