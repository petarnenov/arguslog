import { expect, test } from '../../fixtures/index.js';
import { ProjectsPage } from '../../pages/DashboardPages.js';

/**
 * Project create + rename happy paths through the UI. Complement to `projects.spec.ts`
 * which only exercises list rendering + click-through navigation. Here we drive the
 * actual mutations end-to-end.
 *
 * Org-level cleanup (cascades to the projects we create) runs via the `seededOrg`
 * fixture's teardown — no per-project cleanup needed.
 */
test.describe('projects page — create + rename', () => {
  test('creates a project from the New-project modal and lands on its DSN view', async ({
    authedPage,
    seededOrg,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededOrg.slug);
    await expect(projects.list().or(authedPage.getByTestId('projects-empty-state'))).toBeVisible({
      timeout: 60_000,
    });

    // Open the create modal. Page object's newProjectButton() regex matches "New project".
    await projects.newProjectButton().click();
    const dialog = authedPage.getByRole('dialog');
    await expect(dialog).toBeVisible();

    const projectName = `e2e-proj-crud-${Date.now().toString(36)}`;
    // Inside the create modal there's a TextInput labelled "Project name".
    await dialog.getByLabel(/project name/i).fill(projectName);
    // Quirk: the dashboard reuses the `projects.create` i18n key (= "New project") for
    // BOTH the trigger button and the modal's submit button. The trigger lives outside
    // the dialog so a dialog-scoped match is unambiguous.
    await dialog.getByRole('button', { name: /new project/i }).click();

    // After create, the page opens a DSN success modal (Connect CTA visible). The
    // `dsn-modal-connect-cta` testid is the canonical hook into that modal.
    await expect(authedPage.getByTestId('dsn-modal-connect-cta')).toBeVisible({ timeout: 15_000 });
  });

  test('renames an existing project via the inline edit form', async ({
    authedPage,
    seededProject,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededProject.orgSlug);
    await expect(projects.projectCard(seededProject.slug)).toBeVisible({ timeout: 60_000 });

    // The rename action lives inside a kebab dropdown (Menu.Item with the rename testid is
    // hidden until the kebab ActionIcon is clicked). Open the menu first, then click the
    // testid-anchored Menu.Item.
    await authedPage
      .getByRole('button', { name: new RegExp(`Actions for ${seededProject.name}`, 'i') })
      .click();
    await authedPage.getByTestId(`project-rename-${seededProject.slug}`).click();
    await expect(authedPage.getByTestId('project-edit-form')).toBeVisible();

    const newName = `${seededProject.name}-renamed`;
    const renameInput = authedPage.getByTestId('project-rename-input');
    await renameInput.fill(newName);
    await authedPage.getByTestId('project-rename-submit').click();

    // Renamed name shows up in the project card title. `.first()` because name === slug
    // for inputs without spaces renders in both <p> and <code>.
    await expect(authedPage.getByText(newName).first()).toBeVisible({ timeout: 10_000 });
  });
});
