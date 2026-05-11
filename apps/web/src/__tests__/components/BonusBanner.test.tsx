import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import '../../i18n';
import { BonusBanner } from '../../components/BonusBanner';

function renderBanner(props: Parameters<typeof BonusBanner>[0]) {
  return render(
    <MantineProvider>
      <BonusBanner {...props} />
    </MantineProvider>,
  );
}

describe('BonusBanner', () => {
  const baseBonus = {
    until: '2026-06-01T00:00:00Z',
    reason: null as string | null,
    grantedByEmail: null as string | null,
  };

  it('renders the full variant with plan + body + until timestamp', () => {
    renderBanner({ bonus: baseBonus, plan: 'pro' });
    const banner = screen.getByTestId('bonus-banner');
    expect(banner).toBeInTheDocument();
    expect(banner.textContent).toMatch(/PRO/);
  });

  it('renders the compact variant for the sidebar', () => {
    renderBanner({ bonus: baseBonus, plan: 'starter', variant: 'compact' });
    const banner = screen.getByTestId('bonus-banner-compact');
    expect(banner).toBeInTheDocument();
    expect(banner.textContent?.toLowerCase()).toContain('starter');
  });

  it('shows the reason when provided', () => {
    renderBanner({
      bonus: { ...baseBonus, reason: 'Beta tester' },
      plan: 'pro',
    });
    expect(screen.getByText(/Beta tester/)).toBeInTheDocument();
  });

  it('shows the grantedByEmail when provided', () => {
    renderBanner({
      bonus: { ...baseBonus, grantedByEmail: 'admin@arguslog.org' },
      plan: 'pro',
    });
    expect(screen.getByText(/admin@arguslog\.org/)).toBeInTheDocument();
  });

  it('omits the reason block when reason is null', () => {
    renderBanner({ bonus: baseBonus, plan: 'pro' });
    // No reason text — the only italic dimmed text would carry the reason.
    expect(screen.queryByText(/reason/i)).toBeNull();
  });
});
