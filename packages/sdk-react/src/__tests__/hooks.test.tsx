import { __resetForTests, init } from '@arguslog/sdk-browser';
import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useArgus } from '../hooks.js';

describe('useArgus', () => {
  beforeEach(() => {
    __resetForTests();
  });

  afterEach(() => {
    __resetForTests();
    vi.restoreAllMocks();
  });

  it('exposes the SDK surface', () => {
    const { result } = renderHook(() => useArgus());
    expect(typeof result.current.captureException).toBe('function');
    expect(typeof result.current.captureMessage).toBe('function');
    expect(typeof result.current.addBreadcrumb).toBe('function');
    expect(typeof result.current.setUser).toBe('function');
    expect(typeof result.current.setTag).toBe('function');
    expect(typeof result.current.setContext).toBe('function');
    expect(typeof result.current.isInitialized).toBe('function');
  });

  it('returns a stable object across re-renders', () => {
    const { result, rerender } = renderHook(() => useArgus());
    const first = result.current;
    rerender();
    expect(result.current).toBe(first);
  });

  it('isInitialized reflects client state', () => {
    const { result } = renderHook(() => useArgus());
    expect(result.current.isInitialized()).toBe(false);

    init({
      dsn: 'arguslog://k@localhost:8080/api/1',
      transport: {
        fetch: vi.fn(async () => new Response(null, { status: 202 })) as unknown as typeof fetch,
      },
    });
    expect(result.current.isInitialized()).toBe(true);
  });
});
