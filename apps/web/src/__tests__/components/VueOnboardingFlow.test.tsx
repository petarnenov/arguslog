/**
 * Component test for the Vue workflow-first onboarding panel + verification checklist.
 * Verifies the 7-step structure renders, that DSN inlining works in the env snippet,
 * and that the post-install checklist auto-ticks the "event received" item when the
 * test-ping callback signals success.
 */
import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { VueOnboardingFlow } from '../../components/connect/VueOnboardingFlow';

function renderFlow(overrides: Partial<Parameters<typeof VueOnboardingFlow>[0]> = {}) {
  const onPing = vi.fn();
  const props = {
    dsn: 'arguslog://abc@arguslog.org/api/42',
    pingState: {
      onPing,
      isPending: false,
      result: null,
      ...(overrides.pingState ?? {}),
    },
    ...overrides,
  } as Parameters<typeof VueOnboardingFlow>[0];

  const result = render(
    <MantineProvider defaultColorScheme="light">
      <VueOnboardingFlow {...props} />
    </MantineProvider>,
  );
  return { ...result, onPing };
}

describe('VueOnboardingFlow', () => {
  it('renders all 7 numbered steps', () => {
    renderFlow();
    for (let i = 1; i <= 7; i++) {
      expect(screen.getByTestId(`vue-step-${i}`)).toBeInTheDocument();
    }
  });

  it('inlines the real DSN into the .env.local file snippet', () => {
    renderFlow({ dsn: 'arguslog://realKey@example.com/api/99' });
    // The env snippet appears verbatim in the DOM via a <Code> block.
    expect(
      screen.getByText(/VITE_ARGUSLOG_DSN=arguslog:\/\/realKey@example\.com\/api\/99/),
    ).toBeInTheDocument();
  });

  it('keeps the <DSN> placeholder when no real DSN is available', () => {
    renderFlow({ dsn: null });
    expect(screen.getByText(/VITE_ARGUSLOG_DSN=<DSN>/)).toBeInTheDocument();
  });

  it('triggers the test-ping callback when the verify button is clicked', async () => {
    const user = userEvent.setup();
    const { onPing } = renderFlow();
    await user.click(screen.getByTestId('vue-step-verify-button'));
    expect(onPing).toHaveBeenCalledTimes(1);
  });

  it('auto-ticks the "event received" checklist item on ping success', () => {
    renderFlow({
      pingState: {
        onPing: vi.fn(),
        isPending: false,
        result: { ok: true, detail: 'Event abcdef… accepted.' },
      },
    });
    expect(screen.getByRole('checkbox', { name: /test event received/i })).toBeChecked();
  });

  it('leaves "event received" unchecked when ping has not yet been run', () => {
    renderFlow();
    expect(screen.getByRole('checkbox', { name: /test event received/i })).not.toBeChecked();
  });

  it('shows the verify result alert (success state)', () => {
    renderFlow({
      pingState: {
        onPing: vi.fn(),
        isPending: false,
        result: { ok: true, detail: 'Event 12345678… accepted.' },
      },
    });
    const result = screen.getByTestId('vue-step-verify-result');
    expect(result).toBeInTheDocument();
    expect(result).toHaveTextContent('Event 12345678');
  });
});
