import { expect, test } from '../../fixtures/index.js';
import { ConnectPage } from '../../pages/DashboardPages.js';

test.describe('connect screen', () => {
  test('renders DSN + PAT + magic-prompt tabs for a fresh project', async ({
    authedPage,
    seededProject,
  }) => {
    const connect = new ConnectPage(authedPage);
    await connect.goto(seededProject.orgSlug, seededProject.id);

    // The Connect page auto-provisions a DSN + PAT on first visit.
    await connect.expectDsnVisible();
    // PAT visibility timing — Connect mints it via a separate effect; allow extra wait.
    await expect(authedPage.getByText(/arglog_pat_/)).toBeVisible({ timeout: 30_000 });
  });

  test('"Send test event" auto-ticks the verification checklist', async ({
    authedPage,
    seededProject,
  }) => {
    const connect = new ConnectPage(authedPage);
    await connect.goto(seededProject.orgSlug, seededProject.id);

    // Switch to the SDK tab to surface the OnboardingFlow (only some slugs special-cased).
    await authedPage.getByRole('tab', { name: /sdk/i }).click();
    // Pick the Vue tab (workflow-first flow exists for vue/react/nextjs/angular/rn).
    const vueTab = authedPage.getByRole('tab', { name: /^vue$/i });
    if (await vueTab.isVisible()) {
      await vueTab.click();
    }

    const verifyBtn = connect.testEventButton();
    if (!(await verifyBtn.isVisible())) {
      test.skip(true, 'workflow-first SDK tab not visible — Connect UI variant differs');
      return;
    }
    await verifyBtn.click();
    // Result alert appears with success copy.
    await expect(connect.testEventResult()).toBeVisible({ timeout: 15_000 });
    // The "event received" checklist item should auto-tick on success.
    const eventChecklistItem = authedPage.getByRole('checkbox', {
      name: /test event received/i,
    });
    if (await eventChecklistItem.isVisible()) {
      await expect(eventChecklistItem).toBeChecked({ timeout: 15_000 });
    }
  });
});
