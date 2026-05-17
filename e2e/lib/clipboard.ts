/**
 * Reads the test browser's clipboard. Used by Connect-screen tests that assert
 * the copy-button actually placed the right snippet on the clipboard.
 *
 * Playwright requires the `clipboard-read` permission to be explicitly granted to
 * a context before `navigator.clipboard.readText()` works in the page.
 */
import { type BrowserContext, type Page } from '@playwright/test';

export async function grantClipboardPermissions(context: BrowserContext): Promise<void> {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
}

export async function readClipboard(page: Page): Promise<string> {
  return page.evaluate(() => navigator.clipboard.readText());
}
