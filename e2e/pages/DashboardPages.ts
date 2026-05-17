/**
 * Dashboard page objects — one class per route. Each exposes named methods for
 * the actions a happy-path test exercises (no negative-case helpers, those are
 * out of scope for this round). Selectors prefer ARIA roles + accessible names
 * over CSS selectors so a class rename in Mantine doesn't break the suite.
 */
import { type Page, expect } from '@playwright/test';

export class OrgsLandingPage {
  constructor(public readonly page: Page) {}
  async goto() {
    await this.page.goto('/orgs');
  }
  orgGrid() {
    return this.page.getByTestId('orgs-grid');
  }
  orgCard(slug: string) {
    return this.page.getByTestId(`org-card-${slug}`);
  }
  createOrgButton() {
    return this.page.getByRole('button', { name: /create org|new org/i });
  }
}

export class ProjectsPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string) {
    await this.page.goto(`/orgs/${orgSlug}/projects`);
  }
  list() {
    return this.page.getByTestId('projects-list');
  }
  projectCard(slug: string) {
    return this.page.getByTestId(`project-card-${slug}`);
  }
  newProjectButton() {
    return this.page.getByRole('button', { name: /new project|create project/i });
  }
  async openCreateModal() {
    await this.newProjectButton().click();
    return this.page.getByRole('dialog');
  }
}

export class IssuesPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string, projectId: number) {
    await this.page.goto(`/orgs/${orgSlug}/projects/${projectId}/issues`);
  }
  rows() {
    return this.page.getByTestId('issues-row');
  }
  levelFilter() {
    return this.page.getByRole('combobox', { name: /level/i });
  }
  statusFilter() {
    return this.page.getByRole('combobox', { name: /status/i });
  }
  openIssue(issueId: number) {
    return this.page.getByTestId(`issue-link-${issueId}`).click();
  }
}

export class IssueDetailPage {
  constructor(public readonly page: Page) {}
  stacktrace() {
    return this.page.getByTestId('stacktrace');
  }
  resolveButton() {
    return this.page.getByRole('button', { name: /resolve/i });
  }
  reopenButton() {
    return this.page.getByRole('button', { name: /reopen/i });
  }
  statusBadge() {
    return this.page.getByTestId('issue-status-badge');
  }
}

export class ConnectPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string, projectId: number) {
    await this.page.goto(`/orgs/${orgSlug}/projects/${projectId}/connect`);
  }
  agentTabs() {
    return this.page.getByRole('tablist').first();
  }
  selectAgent(agent: 'claude-code' | 'cursor' | 'codex' | 'copilot' | 'windsurf' | 'continue') {
    return this.page
      .getByRole('tab', { name: new RegExp(agent.replace('-', '[ -]?'), 'i') })
      .click();
  }
  copyButton(snippetId: string) {
    return this.page.getByTestId(`connect-snippet-copy-${snippetId}`);
  }
  testEventButton() {
    return this.page
      .getByTestId('vue-step-verify-button')
      .or(this.page.getByTestId('onboarding-verify-button'));
  }
  testEventResult() {
    return this.page
      .getByTestId('vue-step-verify-result')
      .or(this.page.getByTestId('onboarding-verify-result'));
  }
  async expectDsnVisible() {
    await expect(this.page.getByText(/arguslog:\/\//)).toBeVisible();
  }
  async expectPatVisible() {
    await expect(this.page.getByText(/arglog_pat_/)).toBeVisible();
  }
}

export class ProjectKeysPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string, projectId: number) {
    await this.page.goto(`/orgs/${orgSlug}/projects/${projectId}/keys`);
  }
  keysList() {
    return this.page.getByTestId('keys-list');
  }
  createKeyButton() {
    return this.page.getByRole('button', { name: /create.*key|new.*key/i });
  }
  revokeButton(keyId: number) {
    return this.page.getByTestId(`key-revoke-${keyId}`);
  }
}

export class ReleasesPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string, projectId: number) {
    await this.page.goto(`/orgs/${orgSlug}/projects/${projectId}/releases`);
  }
  list() {
    return this.page.getByTestId('releases-list');
  }
  newReleaseButton() {
    return this.page.getByRole('button', { name: /new release|create release/i });
  }
  releaseRow(version: string) {
    return this.page.getByTestId(`release-row-${version}`);
  }
}

export class AlertRulesPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string, projectId: number) {
    await this.page.goto(`/orgs/${orgSlug}/projects/${projectId}/alert-rules`);
  }
  newRuleButton() {
    return this.page.getByRole('button', { name: /new (alert )?rule|create rule/i });
  }
  rulesList() {
    return this.page.getByTestId('alert-rules-list');
  }
}

export class AlertDestinationsPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string) {
    await this.page.goto(`/orgs/${orgSlug}/destinations`);
  }
  newDestinationButton() {
    return this.page.getByRole('button', { name: /new destination|add destination/i });
  }
  destinationsList() {
    return this.page.getByTestId('destinations-list');
  }
}

export class MembersPage {
  constructor(public readonly page: Page) {}
  async goto(orgSlug: string) {
    await this.page.goto(`/orgs/${orgSlug}/members`);
  }
  inviteButton() {
    return this.page.getByRole('button', { name: /invite/i });
  }
  membersList() {
    return this.page.getByTestId('members-list');
  }
}

export class TokensPage {
  constructor(public readonly page: Page) {}
  async goto() {
    await this.page.goto('/me/tokens');
  }
  createTokenButton() {
    return this.page.getByRole('button', { name: /create.*token|new.*token/i });
  }
  tokensList() {
    return this.page.getByTestId('tokens-list');
  }
}

export class OnboardingPage {
  constructor(public readonly page: Page) {}
  async goto() {
    await this.page.goto('/onboarding');
  }
  form() {
    return this.page.getByTestId('onboarding-form');
  }
  orgNameInput() {
    return this.page.getByLabel(/org.*name|organization.*name/i);
  }
  projectNameInput() {
    return this.page.getByLabel(/project.*name/i);
  }
  submitButton() {
    return this.page.getByRole('button', { name: /create|continue|finish/i });
  }
}
