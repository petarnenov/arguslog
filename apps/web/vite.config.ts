import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Bind to all interfaces so other devices on the LAN can hit
    // http://<dev-machine-ip>:5173 during dev. Localhost still works the same;
    // 0.0.0.0 just additionally accepts the LAN IP. No effect in production —
    // the prod web is served by Caddy, not vite-dev.
    host: true,
  },
  preview: {
    port: 5173,
  },
  build: {
    target: 'es2022',
    sourcemap: true,
  },
});
