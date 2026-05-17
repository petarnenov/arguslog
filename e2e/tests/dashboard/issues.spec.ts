import { expect, test } from '../../fixtures/index.js';
import { ingestSyntheticEvent } from '../../fixtures/testData.js';
import { IssuesPage } from '../../pages/DashboardPages.js';

test.describe('issues list', () => {
  test('issue appears after ingesting a synthetic event', async ({ authedPage, seededDsn }) => {
    await ingestSyntheticEvent(seededDsn.dsn, { message: 'e2e issues happy path' });

    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    // Ingest → fingerprint → issue creation runs through the worker; first-write
    // cold-start can be 5–15s on staging. One generous wait beats reload-spam,
    // which would force fresh useMyOrgs/useProjects fetches every cycle.
    await expect(authedPage.getByText(/E2EFixtureError|e2e issues happy path/i)).toBeVisible({
      timeout: 90_000,
    });
  });

  test('opening an issue row navigates to the detail page', async ({ authedPage, seededDsn }) => {
    await ingestSyntheticEvent(seededDsn.dsn, { message: 'click-through' });
    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    await expect(authedPage.getByText(/click-through/i)).toBeVisible({ timeout: 90_000 });
    await authedPage
      .getByText(/click-through/i)
      .first()
      .click();
    await expect(authedPage).toHaveURL(/\/issues\/\d+$/);
  });
});
