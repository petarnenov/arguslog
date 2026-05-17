import { expect, test } from '../../fixtures/index.js';
import { ingestSyntheticEvent } from '../../fixtures/testData.js';
import { IssuesPage } from '../../pages/DashboardPages.js';

test.describe('issues list', () => {
  test('issue appears after ingesting a synthetic event', async ({ authedPage, seededDsn }) => {
    await ingestSyntheticEvent(seededDsn.dsn, { message: 'e2e issues happy path' });

    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    // Ingest → fingerprint → issue creation runs through the worker. Cold-start
    // worker + first DB write can take ~5-15s on staging; we poll-reload the page
    // so a stale react-query cache doesn't hide the freshly-materialised row.
    await expect
      .poll(
        async () => {
          await authedPage.reload();
          return authedPage.getByText(/E2EFixtureError|e2e issues happy path/i).isVisible();
        },
        { timeout: 60_000, intervals: [3_000, 5_000, 5_000, 10_000] },
      )
      .toBe(true);
  });

  test('opening an issue row navigates to the detail page', async ({ authedPage, seededDsn }) => {
    await ingestSyntheticEvent(seededDsn.dsn, { message: 'click-through' });
    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    await expect
      .poll(
        async () => {
          await authedPage.reload();
          return authedPage.getByText(/click-through/i).isVisible();
        },
        { timeout: 60_000, intervals: [3_000, 5_000, 5_000, 10_000] },
      )
      .toBe(true);

    await authedPage
      .getByText(/click-through/i)
      .first()
      .click();
    await expect(authedPage).toHaveURL(/\/issues\/\d+$/);
  });
});
