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
    // 'tabs' deliberately omitted — the one query in src/shared/domain/connection.ts
    // (`{ active: true, currentWindow: true }`) is the textbook activeTab scenario and
    // doesn't need the broader 'tabs' permission. Smaller permission surface ⇒ faster
    // Web Store review + fewer "why does this need to see all my tabs?" install prompts.
    permissions: ['storage', 'activeTab', 'clipboardWrite', 'downloads', 'sidePanel'],
    host_permissions: ['https://mcp.arguslog.org/*', 'https://*.arguslog.org/*'],
    icons: {
      16: 'icons/16.png',
      32: 'icons/32.png',
      48: 'icons/48.png',
      128: 'icons/128.png',
    },
    action: {
      default_title: 'Arguslog MCP Console',
      default_icon: {
        16: 'icons/16.png',
        32: 'icons/32.png',
      },
    },
    minimum_chrome_version: '116',
  },
});
