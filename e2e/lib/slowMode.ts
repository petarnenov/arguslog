/**
 * Visual-debug slowdown for staging runs. Adds an explicit `setTimeout` pause
 * before every `Page` navigation and every `Page`/`Locator` user action so a
 * human watching the headed browser can see what's happening between steps.
 *
 * Why not just `launchOptions.slowMo`? Playwright's `slowMo` only inserts a
 * delay before CDP-level "actions" (click/fill/press). Tests that are mostly
 * `page.goto(...)` + `expect(...).toBeVisible(...)` get almost no slowdown
 * because assertions aren't CDP actions — that's exactly the shape of this
 * suite (1 click per spec, lots of assertions), so `slowMo: 5000` was
 * effectively invisible. Prototype-patching the methods we DO call gives the
 * watcher a guaranteed pause at every observable step.
 *
 * Gated by `E2E_SLOWMO` env var (ms). Zero or unset → no-op, no patching,
 * zero overhead for CI runs.
 *
 * One-time patch per worker process: the first time a `Page` is handed to a
 * test, we grab its prototype + the `Locator` prototype (by constructing a
 * throw-away locator) and patch the action methods in place. Subsequent
 * `Page`/`Locator` instances in the same worker pick up the patched methods.
 */
import type { Locator, Page } from '@playwright/test';

const SLOW_MO_MS = Number(process.env.E2E_SLOWMO ?? 0);

let patched = false;

const PAGE_METHODS = [
  'goto',
  'reload',
  'goBack',
  'goForward',
  'click',
  'dblclick',
  'fill',
  'type',
  'press',
  'check',
  'uncheck',
  'selectOption',
  'hover',
  'tap',
  'setInputFiles',
] as const;

const LOCATOR_METHODS = [
  'click',
  'dblclick',
  'fill',
  'type',
  'press',
  'check',
  'uncheck',
  'selectOption',
  'hover',
  'tap',
  'setInputFiles',
  'focus',
  'blur',
] as const;

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

function wrapMethods(proto: object, methods: readonly string[]): void {
  for (const name of methods) {
    const descriptor = Object.getOwnPropertyDescriptor(proto, name);
    const original = descriptor?.value;
    if (typeof original !== 'function') continue;
    Object.defineProperty(proto, name, {
      ...descriptor,
      value: async function patched(this: unknown, ...args: unknown[]): Promise<unknown> {
        await wait(SLOW_MO_MS);
        return (original as (...a: unknown[]) => Promise<unknown>).apply(this, args);
      },
    });
  }
}

export function ensureSlowModePatched(page: Page): void {
  if (patched || SLOW_MO_MS <= 0) return;
  patched = true;

  const pageProto = Object.getPrototypeOf(page) as object;
  // Construct a throw-away locator so we can grab its prototype. The selector
  // is never resolved — `page.locator(':root')` just returns a Locator handle.
  const locatorProto = Object.getPrototypeOf(page.locator(':root') as Locator) as object;

  wrapMethods(pageProto, PAGE_METHODS);
  wrapMethods(locatorProto, LOCATOR_METHODS);

  console.warn(`[slowMode] patched Page+Locator methods with ${SLOW_MO_MS}ms pre-action delay`);
}
