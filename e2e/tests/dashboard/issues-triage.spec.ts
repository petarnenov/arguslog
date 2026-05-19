import { expect, test } from '../../fixtures/index.js';
import { ingestSyntheticEvent } from '../../fixtures/testData.js';
import { IssuesPage } from '../../pages/DashboardPages.js';

/**
 * Issues triage happy paths. The existing `issues.spec.ts` only asserts that an ingested
 * event lands as a row + click-through to detail; this spec exercises the actual triage
 * surface: the level filter narrows the visible list.
 *
 * We deliberately scope to FILTER (not assign / resolve) — those are covered by
 * `issue-detail.spec.ts`. The triage interactions are the most visible "I clicked the
 * thing and the list reacted" happy path for the issues page.
 */
test.describe('issues — triage filters', () => {
  test('ingested event shows up and the level filter narrows the list', async ({
    authedPage,
    seededDsn,
  }) => {
    await ingestSyntheticEvent(seededDsn.dsn, { message: 'E2E triage filter probe' });

    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    // Worker processes async — give the row a generous window. Reload until at least
    // one row renders; the 60s budget matches Railway cold-start + worker poll cadence.
    await expect
      .poll(
        async () => {
          const count = await issues.rows().count();
          if (count === 0) await authedPage.reload();
          return count;
        },
        { timeout: 60_000 },
      )
      .toBeGreaterThanOrEqual(1);

    const baselineCount = await issues.rows().count();

    // Pick a level that almost certainly excludes the synthetic event (synthetic events
    // are `level: 'error'`). Filtering to "warning" should collapse the list.
    await issues.levelFilter().click();
    await authedPage.getByRole('option', { name: /warning/i }).click();

    await expect
      .poll(async () => issues.rows().count(), { timeout: 10_000 })
      .toBeLessThan(baselineCount);
  });
});
