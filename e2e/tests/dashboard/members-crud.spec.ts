import { runnerTierIsAtLeast } from '../../fixtures/auth.js';
import { expect, test } from '../../fixtures/index.js';
import { MembersPage } from '../../pages/DashboardPages.js';

/**
 * Member invite happy path. We can't accept the invite end-to-end in e2e (would need a
 * second Keycloak account + the inviter to be on a separate session), so the happy path
 * stops at "invite was issued, pending row visible". The actual invite-accept flow is
 * covered by the API integration suite.
 *
 * Tier gate: the `regular` tier caps orgs at 1 member, which makes the invite return
 * 402 PaymentRequired and the modal stays open with an Alert. To keep the suite green
 * on a regular-tier runner (e.g. the staging demo user) we skip cleanly — the same
 * pattern tokens-crud uses for its real-KC requirement.
 */
test.describe('org members — invite', () => {
  test('issues an invite and the email shows up as pending in the members table', async ({
    authedPage,
    seededOrg,
  }) => {
    test.skip(
      !(await runnerTierIsAtLeast('silver')),
      'regular tier caps orgs at 1 member; invite would 402. Needs silver+ runner.',
    );

    const members = new MembersPage(authedPage);
    await members.goto(seededOrg.slug);
    // Generous timeout — staging cold-start + org-provision propagation can briefly
    // delay the members query past the default 15s budget.
    await expect(members.membersList()).toBeVisible({ timeout: 30_000 });

    await members.inviteButton().click();
    const inviteForm = authedPage.getByTestId('invite-form');
    await expect(inviteForm).toBeVisible({ timeout: 10_000 });

    // Use a per-run unique email so concurrent runs never collide on the org's
    // pending-invite uniqueness constraint.
    const email = `e2e-invitee-${Date.now().toString(36)}@example.com`;
    await inviteForm.getByLabel(/email/i).fill(email);
    // Role defaults to "member" — leave it.
    // The form's submit button reads "Send invite" (i18n key members.send), not "Invite" —
    // the modal title is "Invite a member" but the action verb is "Send".
    await inviteForm.getByRole('button', { name: /send invite/i }).click();

    // The invite mutation's `onSuccess` (a) invalidates the members query and (b) closes
    // the modal. Wait for the modal to close as the canonical "mutation completed" signal
    // — on staging this round-trip takes longer than the table-render assertion timeout.
    await expect(inviteForm).toBeHidden({ timeout: 30_000 });

    // Pending row lands in the members table. The accessible row name interleaves invitee
    // handle / Pending badge / email / role / date — order makes a literal regex brittle,
    // so anchor by the row filtered to contain BOTH the unique email AND the "Pending"
    // badge text.
    await expect(
      authedPage
        .getByRole('row')
        .filter({ hasText: email })
        .filter({ hasText: /pending/i }),
    ).toBeVisible({ timeout: 10_000 });
  });
});
