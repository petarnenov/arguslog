import { Badge, Code, Group, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';

/**
 * Frame as it lands in the persisted event payload. Worker symbolication (P3 #10) layers the
 * {@code original*} fields on top of the SDK's raw fields when a sourcemap matches the release.
 * The raw fields are always preserved so the dashboard can show both via the toggle in
 * {@link StacktraceView}.
 */
export interface RawFrame {
  filename?: string;
  function?: string;
  lineno?: number;
  colno?: number;
  originalFilename?: string;
  originalFunction?: string;
  originalLineno?: number;
  originalColno?: number;
}

/** Walks {@code payload.exception.values[*].stacktrace.frames[*]} into a flat list. */
export function extractFrames(payload: unknown): RawFrame[] {
  if (!payload || typeof payload !== 'object') return [];
  const exception = (payload as { exception?: { values?: unknown } }).exception?.values;
  if (!Array.isArray(exception)) return [];
  const frames: RawFrame[] = [];
  for (const value of exception) {
    if (!value || typeof value !== 'object') continue;
    const stack = (value as { stacktrace?: { frames?: unknown } }).stacktrace?.frames;
    if (Array.isArray(stack)) {
      for (const frame of stack) {
        if (frame && typeof frame === 'object') frames.push(frame as RawFrame);
      }
    }
  }
  return frames;
}

/** True when at least one frame carries any decoded {@code original*} field. */
export function hasSymbolication(frames: readonly RawFrame[]): boolean {
  return frames.some(
    (f) =>
      f.originalFilename != null ||
      f.originalFunction != null ||
      f.originalLineno != null ||
      f.originalColno != null,
  );
}

export interface StacktraceViewProps {
  frames: readonly RawFrame[];
  /** When true and a frame has original* fields, prefer those; raw fallback otherwise. */
  preferOriginal: boolean;
}

export function StacktraceView({ frames, preferOriginal }: StacktraceViewProps) {
  const { t } = useTranslation();
  if (frames.length === 0) return null;

  // SDK convention is leaf-last; reverse so the throwing frame shows first (matches Sentry UX).
  const ordered = [...frames].reverse();

  return (
    <Stack gap={4} data-testid="stacktrace">
      {ordered.map((frame, idx) => {
        const useOriginal = preferOriginal && frameHasOriginal(frame);
        const fn =
          (useOriginal ? frame.originalFunction : null) ??
          frame.function ??
          t('issueDetail.anonymousFrame');
        const file = (useOriginal ? frame.originalFilename : null) ?? frame.filename ?? '?';
        const line = (useOriginal ? frame.originalLineno : null) ?? frame.lineno;
        const col = (useOriginal ? frame.originalColno : null) ?? frame.colno;
        const location = formatLocation(file, line, col);

        return (
          <Group key={idx} gap="xs" wrap="nowrap">
            <Text size="sm" fw={500} c={useOriginal ? undefined : 'dimmed'}>
              {fn}
            </Text>
            <Text size="xs" c="dimmed">
              {t('issueDetail.frameAt')}
            </Text>
            <Code>{location}</Code>
            {useOriginal && (
              <Badge variant="light" size="xs" color="green">
                {t('issueDetail.original')}
              </Badge>
            )}
          </Group>
        );
      })}
    </Stack>
  );
}

function frameHasOriginal(frame: RawFrame): boolean {
  return (
    frame.originalFilename != null ||
    frame.originalFunction != null ||
    frame.originalLineno != null ||
    frame.originalColno != null
  );
}

function formatLocation(file: string, line?: number, col?: number): string {
  if (line == null) return file;
  if (col == null) return `${file}:${line}`;
  return `${file}:${line}:${col}`;
}
