import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Guards the three-stage runtime config resolution: window.__ARGUSLOG_CONFIG__ wins over
 * import.meta.env wins over devDefault. Empty strings from the entrypoint must be treated as
 * "unset" so a self-hoster who leaves one var blank doesn't ship `""` URLs to fetch / OIDC.
 *
 * Each test re-imports the module after mutating window so the zod-parsed singleton picks up
 * the new state (env.ts runs its parse at import time, not on every read).
 */
describe('env runtime-config resolution', () => {
  const originalConfig = window.__ARGUSLOG_CONFIG__;

  beforeEach(() => {
    vi.resetModules();
    window.__ARGUSLOG_CONFIG__ = {};
  });

  afterEach(() => {
    window.__ARGUSLOG_CONFIG__ = originalConfig;
  });

  it('runtime config wins over the build-time fallback', async () => {
    window.__ARGUSLOG_CONFIG__ = {
      apiBaseUrl: 'https://api.self-host.example.com',
      keycloakRealm: 'corp-realm',
    };
    const { env } = await import('../env');
    expect(env.VITE_API_BASE_URL).toBe('https://api.self-host.example.com');
    expect(env.VITE_KEYCLOAK_REALM).toBe('corp-realm');
  });

  it('empty strings from the entrypoint fall through to the build-time / dev default', async () => {
    window.__ARGUSLOG_CONFIG__ = {
      apiBaseUrl: '',
      keycloakRealm: '   ',
    };
    const { env } = await import('../env');
    // Default realm comes from the zod schema fallback, NOT from the empty runtime value.
    expect(env.VITE_KEYCLOAK_REALM).toBe('arguslog');
    // API URL falls through to the localhost-follows-hostname dev default.
    expect(env.VITE_API_BASE_URL).toMatch(/:8081$/);
  });

  it('release stamp passes through runtime override (no protocol parsing constraint)', async () => {
    window.__ARGUSLOG_CONFIG__ = { release: 'self-host-2026-05-15' };
    const { env } = await import('../env');
    expect(env.VITE_RELEASE).toBe('self-host-2026-05-15');
  });

  it('dogfoodDsn is optional and stays undefined when unset', async () => {
    window.__ARGUSLOG_CONFIG__ = {};
    const { env } = await import('../env');
    expect(env.VITE_DOGFOOD_DSN).toBeUndefined();
  });
});
