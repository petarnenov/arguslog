import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/unit/setup.ts'],
    // Playwright e2e specs live in tests/e2e/ and require a real chromium binary —
    // they're not vitest-runnable. Default include pattern would pick them up; exclude
    // explicitly so `pnpm test` stays unit-only and `pnpm e2e` owns the real-browser
    // suite.
    include: ['tests/unit/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['tests/e2e/**', 'node_modules/**', 'dist/**', '.output/**'],
  },
});
