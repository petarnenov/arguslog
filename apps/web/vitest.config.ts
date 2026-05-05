import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/__tests__/**',
        'src/main.tsx',
        'src/test-setup.ts',
        'src/vite-env.d.ts',
        // P0 placeholders — re-include as real logic + tests land in P2
        'src/pages/**',
        'src/layouts/**',
        'src/router.tsx',
        'src/providers.tsx',
        'src/env.ts',
      ],
      thresholds: { statements: 75, branches: 70, functions: 75, lines: 75 },
    },
  },
});
