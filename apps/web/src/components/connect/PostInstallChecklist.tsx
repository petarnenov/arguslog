/**
 * Post-install verification checklist for the workflow-first Vue onboarding flow.
 *
 * Phase B of arguslog-sdks#2: instead of leaving the operator to figure out whether
 * onboarding is "done", we surface the 7 concrete checkboxes the issue author asked
 * for. State is local (no persistence, no cross-session memory). Step 6 — "test
 * event received" — is the only checkbox we can auto-tick mechanically: the parent
 * passes an `eventReceived` boolean that flips true after the existing test-ping
 * succeeds. Everything else is a manual tick the operator owns.
 */
import { Checkbox, Paper, Stack, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';

export interface ChecklistItem {
  id: string;
  label: string;
}

interface Props {
  items: readonly ChecklistItem[];
  /** Set true when the test-event ping has succeeded; auto-ticks the matching item. */
  eventReceived: boolean;
  /** Item id to auto-tick when `eventReceived` flips true. Defaults to `'event'`. */
  autoTickOnEventId?: string;
}

export function PostInstallChecklist({ items, eventReceived, autoTickOnEventId = 'event' }: Props) {
  const [checked, setChecked] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (eventReceived) {
      setChecked((prev) =>
        prev[autoTickOnEventId] ? prev : { ...prev, [autoTickOnEventId]: true },
      );
    }
  }, [eventReceived, autoTickOnEventId]);

  const ticked = items.filter((i) => checked[i.id]).length;

  return (
    <Paper p="md" withBorder radius="md" data-testid="post-install-checklist">
      <Stack gap="xs">
        <Title order={5}>Verification checklist</Title>
        <Text size="xs" c="dimmed">
          {ticked} of {items.length} complete — tick as you go.
        </Text>
        <Stack gap={6}>
          {items.map((item) => (
            <Checkbox
              key={item.id}
              size="sm"
              label={item.label}
              checked={!!checked[item.id]}
              // Read `checked` synchronously BEFORE scheduling the state update. React's
              // updater closure runs later (during the next render flush) — by then the
              // input may have been re-mounted (the parent OnboardingFlow re-renders on
              // every `eventReceived` flip) and `e.currentTarget` would be null, throwing
              // `Cannot read properties of null (reading 'checked')` on the second click.
              onChange={(e) => {
                const next = e.currentTarget.checked;
                setChecked((prev) => ({ ...prev, [item.id]: next }));
              }}
              data-testid={`checklist-item-${item.id}`}
            />
          ))}
        </Stack>
      </Stack>
    </Paper>
  );
}
