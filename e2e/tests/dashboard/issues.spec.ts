import { expect, test } from '../../fixtures/index.js';
import { ingestSyntheticEvent } from '../../fixtures/testData.js';
import { IssuesPage } from '../../pages/DashboardPages.js';

test.describe('issues list', () => {
  test('after ingesting a synthetic event the issue shows up', async ({
    authedPage,
    seededDsn,
  }) => {
    await ingestSyntheticEvent(seededDsn.dsn, { message: 'e2e issues happy path' });

    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    // Ingest → fingerprint → issue creation runs through the worker. On staging this
    // typically lands within ~3s; the polling timeout here is generous to absorb
    // cold-start latency.
    await expect(authedPage.getByText(/E2EFixtureError|e2e issues happy path/i)).toBeVisible({
      timeout: 30_000,
    });
  });

  test('clicking an issue row opens the detail page', async ({ authedPage, seededDsn }) => {
    await ingestSyntheticEvent(seededDsn.dsn, { message: 'click-through' });
    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    const row = authedPage.getByText(/click-through/i).first();
    await expect(row).toBeVisible({ timeout: 30_000 });
    await row.click();
    await expect(authedPage).toHaveURL(/\/issues\/\d+$/);
  });
});
