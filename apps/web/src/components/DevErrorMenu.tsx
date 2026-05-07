import { captureException } from '@arguslog/sdk-react';
import { ActionIcon, Menu, Tooltip } from '@mantine/core';
import { IconBug, IconBolt, IconAlertOctagon, IconBrandReact, IconWand } from '@tabler/icons-react';
import { useState } from 'react';

/**
 * Dev-only quick-trigger menu for exercising the dogfood SDK end-to-end. Each item exercises a
 * different capture path; check the issues page after firing to confirm the event landed.
 *
 * The component returns null in production so it's never rendered for real users — guard at
 * the call site is belt-and-suspenders, but keeping the runtime check inside the component
 * means any accidental import in a future entrypoint stays safe.
 */
export function DevErrorMenu() {
  const [renderBoom, setRenderBoom] = useState(false);
  if (!import.meta.env.DEV) return null;

  // When this state flips, the line below throws during render — that path goes through
  // ArguslogErrorBoundary (React's error boundary contract), not window.onerror.
  if (renderBoom) {
    throw new Error('DevErrorMenu: simulated React render error');
  }

  const stamp = () => new Date().toISOString().slice(11, 19);

  return (
    <Menu position="bottom-end" withArrow shadow="md">
      <Menu.Target>
        <Tooltip label="Throw test error (dev only)" position="bottom" withArrow>
          <ActionIcon variant="subtle" color="orange" aria-label="Throw test error" size="lg">
            <IconBug size={18} />
          </ActionIcon>
        </Tooltip>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>SDK capture paths</Menu.Label>
        <Menu.Item
          leftSection={<IconBolt size={14} />}
          onClick={() => {
            // Async throw → window.onerror → globalHandlers integration → SDK
            setTimeout(() => {
              throw new Error(`async throw @ ${stamp()}`);
            }, 0);
          }}
        >
          Async throw (setTimeout)
        </Menu.Item>
        <Menu.Item
          leftSection={<IconAlertOctagon size={14} />}
          onClick={() => {
            // Promise rejection without catch → window.onunhandledrejection → SDK
            void Promise.reject(new Error(`unhandled rejection @ ${stamp()}`));
          }}
        >
          Unhandled promise rejection
        </Menu.Item>
        <Menu.Item
          leftSection={<IconWand size={14} />}
          onClick={() => {
            // Direct API call — bypasses window handlers, exercises captureException + transport
            captureException(new Error(`direct capture @ ${stamp()}`), {
              level: 'error',
              tags: { source: 'dev-menu', kind: 'direct' },
            });
          }}
        >
          Direct captureException
        </Menu.Item>
        <Menu.Item
          color="red"
          leftSection={<IconBrandReact size={14} />}
          onClick={() => setRenderBoom(true)}
        >
          React render error (boundary)
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
