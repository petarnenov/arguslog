import { expect, test } from '../../fixtures/index.js';
import { ProjectKeysPage } from '../../pages/DashboardPages.js';

test.describe('project keys page', () => {
  test('lists the auto-provisioned DSN', async ({ authedPage, seededDsn }) => {
    const keys = new ProjectKeysPage(authedPage);
    await keys.goto(seededDsn.project.orgSlug, seededDsn.project.id);
    // DSN public key is rendered (full DSN string would leak the secret half — only public).
    await expect(authedPage.getByText(seededDsn.dsn.publicKey)).toBeVisible({ timeout: 15_000 });
  });
});
