/**
 * Combined Playwright test fixture. Use `import { test, expect } from '../fixtures'`
 * in any spec — the standard Playwright `{ page }` arg is automatically authed
 * (where applicable), and `{ seededOrg, seededProject, seededDsn }` are provisioned
 * fresh per test with auto-cleanup.
 *
 * Three fixture levels of opt-in (use the lightest one your test needs):
 *
 *   1. `authedPage` — page with OIDC user blob pre-seeded; user authenticated, but
 *      no org/project/DSN created. Cheap.
 *   2. `seededOrg` — adds an empty fresh org. Cascades cleanup.
 *   3. `seededProject` — adds an org + a project inside it. Cascades cleanup.
 *   4. `seededDsn` — adds an org + project + active DSN. Cascades cleanup.
 *
 * Each fixture depends on the lower ones, so requesting `seededDsn` automatically
 * provisions everything below it. Cleanup runs from top down (org delete cascades
 * to project + DSN), so each fixture only needs to register its own org-level teardown.
 */
import { test as base, expect, type Page } from '@playwright/test';

import { loginAsTestUser } from './auth.js';
import {
  createDsn,
  createOrg,
  createProject,
  deleteOrg,
  type SeededDsn,
  type SeededOrg,
  type SeededProject,
} from './testData.js';

interface ArguslogFixtures {
  /** Pre-authenticated page. Navigate freely; OIDC user blob is seeded before page boot. */
  authedPage: Page;
  /** Fresh empty org. Cleaned up after test. */
  seededOrg: SeededOrg;
  /** Fresh org + project. Project parent org is cleaned up after test (cascades). */
  seededProject: SeededProject;
  /** Fresh org + project + DSN. Org cleanup cascades the rest. */
  seededDsn: { dsn: SeededDsn; project: SeededProject; org: SeededOrg };
}

export const test = base.extend<ArguslogFixtures>({
  authedPage: async ({ page }, use) => {
    await loginAsTestUser(page);
    await use(page);
  },

  // eslint-disable-next-line no-empty-pattern -- Playwright fixture signature requires the destructuring slot even when no parent fixtures are used.
  seededOrg: async ({}, use) => {
    const org = await createOrg();
    try {
      await use(org);
    } finally {
      await deleteOrg(org.id);
    }
  },

  seededProject: async ({ seededOrg }, use) => {
    const project = await createProject(seededOrg.id);
    // Org-level cleanup cascades — no per-project teardown needed.
    await use(project);
  },

  seededDsn: async ({ seededProject, seededOrg }, use) => {
    const dsn = await createDsn(seededProject.id);
    await use({ dsn, project: seededProject, org: seededOrg });
  },
});

export { expect };
export { e2eConfig } from '../playwright.config.js';
