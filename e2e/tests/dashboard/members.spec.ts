import { expect, test } from '../../fixtures/index.js';
import { MembersPage } from '../../pages/DashboardPages.js';

test.describe('org members page', () => {
  test('test user is listed as an Owner of their seeded org', async ({ authedPage, seededOrg }) => {
    const members = new MembersPage(authedPage);
    await members.goto(seededOrg.slug);
    // Heading + at least one row visible. The members table renders only after
    // both the org + members queries resolve, hence the generous timeout.
    await expect(authedPage.getByRole('heading', { name: /members/i })).toBeVisible({
      timeout: 15_000,
    });
    await expect(members.membersList()).toBeVisible({ timeout: 15_000 });
  });
});
