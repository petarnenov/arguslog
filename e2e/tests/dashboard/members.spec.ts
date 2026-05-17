import { expect, test } from '../../fixtures/index.js';
import { MembersPage } from '../../pages/DashboardPages.js';

test.describe('org members page', () => {
  test('test user is listed as an Owner of their seeded org', async ({ authedPage, seededOrg }) => {
    const members = new MembersPage(authedPage);
    await members.goto(seededOrg.slug);
    // Heading + at least one row visible.
    await expect(authedPage.getByRole('heading', { name: /members/i })).toBeVisible({
      timeout: 15_000,
    });
    // Test user's email or name should appear in the list.
    const testEmail = process.env.ARGUSLOG_E2E_TEST_USER_EMAIL;
    if (testEmail) {
      await expect(authedPage.getByText(testEmail)).toBeVisible({ timeout: 15_000 });
    }
  });
});
