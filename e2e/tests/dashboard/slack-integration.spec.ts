import { expect, test } from '../../fixtures/index.js';

/**
 * Slack integrations page happy path. For a fresh org (no Slack workspaces connected) the
 * page renders the "Connect Slack" CTA (`data-testid="slack-connect-button"`). We can't
 * actually run the OAuth flow in e2e (would hit Slack's real OAuth + need a workspace),
 * so the happy path stops at "CTA visible". This is consistent with how the rest of the
 * suite handles external-OAuth flows.
 */
test.describe('Slack integrations page', () => {
  test('renders connect-Slack CTA for a fresh org with no workspaces', async ({
    authedPage,
    seededOrg,
  }) => {
    await authedPage.goto(`/orgs/${seededOrg.slug}/integrations/slack`);

    // Page heading — i18n key `slack.title`. Just assert the route loaded.
    await expect(authedPage.getByRole('heading', { name: /slack/i }).first()).toBeVisible({
      timeout: 15_000,
    });

    // Empty-state CTA must be present so a new org can begin the install flow.
    await expect(authedPage.getByTestId('slack-connect-button')).toBeVisible();
  });
});
