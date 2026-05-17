/**
 * Component test for the generic workflow-first onboarding panel. Parametrized over
 * every SDK_CATALOG slug that carries an `initFiles[]` shape (currently vue + react;
 * next.js / angular / react-native land later in the cross-SDK rework).
 *
 * Per slug we assert:
 *   - All steps render (Install + N file steps + recommended-arch if present +
 *     Verify + optional boundary). Count matches what the catalog declares.
 *   - The real DSN is inlined into the env-file step where the catalog uses
 *     `<DSN>` as a placeholder.
 *   - Clicking the Verify button calls the parent `onPing` callback.
 *   - The "event received" checklist item auto-ticks on a successful ping result.
 *   - With no DSN we keep the `<DSN>` placeholder (no false-positive substitution).
 */
import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { OnboardingFlow } from '../../components/connect/OnboardingFlow';
import { SDK_CATALOG } from '../../lib/connectSnippets';

const WORKFLOW_FIRST_SLUGS = SDK_CATALOG.filter((e) => 'initFiles' in e && e.initFiles).map(
  (e) => e.slug,
);

function renderFlow(slug: string, overrides: Partial<Parameters<typeof OnboardingFlow>[0]> = {}) {
  const onPing = vi.fn();
  const props: Parameters<typeof OnboardingFlow>[0] = {
    slug,
    dsn: 'arguslog://abc@arguslog.org/api/42',
    pingState: {
      onPing,
      isPending: false,
      result: null,
      ...(overrides.pingState ?? {}),
    },
    ...overrides,
  };

  const result = render(
    <MantineProvider defaultColorScheme="light">
      <OnboardingFlow {...props} />
    </MantineProvider>,
  );
  return { ...result, onPing };
}

function expectedStepCount(slug: string): number {
  const entry = SDK_CATALOG.find((e) => e.slug === slug);
  if (!entry || !('initFiles' in entry) || !entry.initFiles) {
    throw new Error(`expectedStepCount: slug ${slug} has no initFiles`);
  }
  let count = 1; // Step 1: install
  count += entry.initFiles.length; // N file steps
  const extras = 'extras' in entry ? entry.extras : undefined;
  if (extras?.recommendedArchitecture?.files?.length) count += 1;
  count += 1; // Verify
  if ('wrapSnippet' in entry && entry.wrapSnippet) count += 1;
  return count;
}

describe.each(WORKFLOW_FIRST_SLUGS)('OnboardingFlow — %s', (slug) => {
  it(`renders exactly the catalog's expected step count`, () => {
    renderFlow(slug);
    const expected = expectedStepCount(slug);
    for (let i = 1; i <= expected; i++) {
      expect(screen.getByTestId(`onboarding-step-${i}`)).toBeInTheDocument();
    }
    // One past the last should not exist.
    expect(screen.queryByTestId(`onboarding-step-${expected + 1}`)).toBeNull();
  });

  it('inlines the real DSN into the env-file step', () => {
    renderFlow(slug, { dsn: 'arguslog://realKey@example.com/api/99' });
    const entry = SDK_CATALOG.find((e) => e.slug === slug)!;
    const initFiles = 'initFiles' in entry && entry.initFiles ? entry.initFiles : [];
    const envFile = initFiles.find((f) => f.path.startsWith('.env'));
    if (!envFile) return; // Some SDKs (later: Angular) may not have a .env step.
    expect(screen.getByText(/arguslog:\/\/realKey@example\.com\/api\/99/)).toBeInTheDocument();
  });

  it('keeps the <DSN> placeholder when no DSN is available', () => {
    renderFlow(slug, { dsn: null });
    const entry = SDK_CATALOG.find((e) => e.slug === slug)!;
    const initFiles = 'initFiles' in entry && entry.initFiles ? entry.initFiles : [];
    const hasDsnPlaceholder = initFiles.some((f) => f.contents.includes('<DSN>'));
    if (!hasDsnPlaceholder) return;
    expect(screen.getAllByText(/<DSN>/).length).toBeGreaterThan(0);
  });

  it('verify button calls the parent ping callback', async () => {
    const user = userEvent.setup();
    const { onPing } = renderFlow(slug);
    await user.click(screen.getByTestId('onboarding-verify-button'));
    expect(onPing).toHaveBeenCalledTimes(1);
  });

  it('auto-ticks the "event received" checklist item on ping success', () => {
    renderFlow(slug, {
      pingState: {
        onPing: vi.fn(),
        isPending: false,
        result: { ok: true, detail: 'Event abcdef… accepted.' },
      },
    });
    const entry = SDK_CATALOG.find((e) => e.slug === slug)!;
    const checklist = 'extras' in entry ? entry.extras?.verificationChecklist : undefined;
    const eventItem = checklist?.find((i) => i.id === 'event');
    if (!eventItem) return; // SDKs without `event` item are exempt.
    const labelMatcher = new RegExp(eventItem.label.replace(/[`*[\]()]/g, '.').slice(0, 30), 'i');
    expect(screen.getByRole('checkbox', { name: labelMatcher })).toBeChecked();
  });

  it('shows the verify result alert on success', () => {
    renderFlow(slug, {
      pingState: {
        onPing: vi.fn(),
        isPending: false,
        result: { ok: true, detail: 'Event 12345678… accepted.' },
      },
    });
    const result = screen.getByTestId('onboarding-verify-result');
    expect(result).toHaveTextContent('Event 12345678');
  });

  // Regression: the prior onChange handler read `e.currentTarget.checked` inside a
  // setState updater closure that ran during the next render flush — by which time
  // the input had been re-mounted (the parent re-renders on every `eventReceived`
  // flip) and `currentTarget` was null. Double-click toggling crashed with
  // "Cannot read properties of null (reading 'checked')". Use userEvent (real DOM
  // events) — fireEvent.change synthesises a complete event and would NOT have
  // surfaced the original bug.
  it('toggles a checklist item twice without throwing (regression for null currentTarget)', async () => {
    const user = userEvent.setup();
    const entry = SDK_CATALOG.find((e) => e.slug === slug)!;
    const checklist = 'extras' in entry ? entry.extras?.verificationChecklist : undefined;
    const firstItem = checklist?.[0];
    if (!firstItem) return;

    renderFlow(slug);
    const labelMatcher = new RegExp(firstItem.label.replace(/[`*[\]()]/g, '.').slice(0, 30), 'i');
    const checkbox = screen.getByRole('checkbox', { name: labelMatcher });

    expect(checkbox).not.toBeChecked();
    await user.click(checkbox);
    expect(checkbox).toBeChecked();
    await user.click(checkbox);
    expect(checkbox).not.toBeChecked();
  });
});
