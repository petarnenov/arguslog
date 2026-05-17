import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'wxt';

export default defineConfig({
  srcDir: 'src',
  modules: ['@wxt-dev/module-react'],
  vite: () => ({
    plugins: [tailwindcss()],
  }),
  manifest: {
    name: 'Arguslog MCP Console',
    description:
      'Chromium-first operator console for Arguslog MCP with curated issue, release, workflow, and tool surfaces.',
    permissions: ['storage', 'tabs', 'activeTab', 'clipboardWrite', 'downloads', 'sidePanel'],
    host_permissions: ['https://mcp.arguslog.org/*', 'https://*.arguslog.org/*'],
    action: {
      default_title: 'Arguslog MCP Console',
    },
    minimum_chrome_version: '116',
  },
});
