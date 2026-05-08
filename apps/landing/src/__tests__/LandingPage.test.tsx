import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { LandingPage } from '../pages/LandingPage';
import { Providers } from '../providers';

const originalFetch = globalThis.fetch;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderLanding() {
  return render(
    <Providers>
      <LandingPage />
    </Providers>,
  );
}

describe('LandingPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('renders the hero CTAs', () => {
    globalThis.fetch = vi.fn(async () => jsonResponse([])) as typeof fetch;
    renderLanding();

    // Hero copy.
    expect(screen.getByText(/Catch errors before your customers do/i)).toBeInTheDocument();
    // Primary CTA + secondary GitHub button — both must be linkable.
    const ctaPrimary = screen.getAllByRole('link', { name: /Get started free/i })[0];
    expect(ctaPrimary).toHaveAttribute('href', expect.stringContaining('/onboarding'));
  });

  it('renders the platforms catalog when the api returns data', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/platforms')) {
        return jsonResponse([
          { slug: 'javascript', name: 'JavaScript / Browser', sdkPackage: '@arguslog/sdk-browser', sdkVersion: '1.0.0' },
          { slug: 'react-native', name: 'React Native', sdkPackage: '@arguslog/sdk-react-native', sdkVersion: '1.0.0' },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderLanding();

    await waitFor(() => {
      expect(screen.getAllByTestId('platform-card')).toHaveLength(2);
    });
    expect(screen.getByText('JavaScript / Browser')).toBeInTheDocument();
    expect(screen.getByText('React Native')).toBeInTheDocument();
  });

  it('falls back gracefully when the platforms api fails', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse({ title: 'boom' }, 500)) as typeof fetch;

    renderLanding();

    // Providers config has retry: 1 with exponential backoff, so the error state takes ~1s+
    // to settle. Bump waitFor's default timeout so we don't race the retry.
    await waitFor(
      () => {
        expect(screen.getByText(/Visit the GitHub repo for the SDK list/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });
});
