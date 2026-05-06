import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import '../../i18n';
import {
  extractFrames,
  hasSymbolication,
  type RawFrame,
  StacktraceView,
} from '../../pages/issue-detail/Stacktrace';

describe('extractFrames', () => {
  it('flattens frames across all exception values', () => {
    const payload = {
      exception: {
        values: [
          { stacktrace: { frames: [{ filename: 'a.js', lineno: 1 }] } },
          { stacktrace: { frames: [{ filename: 'b.js', lineno: 2 }] } },
        ],
      },
    };
    expect(extractFrames(payload)).toHaveLength(2);
  });

  it('returns [] when payload is missing or malformed', () => {
    expect(extractFrames(null)).toEqual([]);
    expect(extractFrames({})).toEqual([]);
    expect(extractFrames({ exception: { values: 'nope' } })).toEqual([]);
    expect(extractFrames({ exception: { values: [{}] } })).toEqual([]);
  });
});

describe('hasSymbolication', () => {
  it('is true when any frame has any original* field', () => {
    const frames: RawFrame[] = [
      { filename: 'a.js', lineno: 1 },
      { filename: 'b.js', lineno: 2, originalFilename: 'src/b.ts' },
    ];
    expect(hasSymbolication(frames)).toBe(true);
  });

  it('is false when no frame has any original* field', () => {
    expect(hasSymbolication([{ filename: 'a.js', lineno: 1 }])).toBe(false);
    expect(hasSymbolication([])).toBe(false);
  });
});

describe('StacktraceView', () => {
  function renderView(props: { frames: RawFrame[]; preferOriginal: boolean }) {
    return render(
      <MantineProvider>
        <StacktraceView {...props} />
      </MantineProvider>,
    );
  }

  const symbolicatedFrame: RawFrame = {
    filename: 'dist/app.abc.js',
    function: 'r',
    lineno: 1,
    colno: 42,
    originalFilename: 'src/app.ts',
    originalFunction: 'render',
    originalLineno: 10,
    originalColno: 4,
  };

  it('renders the original location and the original badge when preferOriginal=true', () => {
    renderView({ frames: [symbolicatedFrame], preferOriginal: true });
    expect(screen.getByText('render')).toBeInTheDocument();
    expect(screen.getByText('src/app.ts:10:4')).toBeInTheDocument();
    expect(screen.getByText(/original/i)).toBeInTheDocument();
  });

  it('renders the raw location when preferOriginal=false', () => {
    renderView({ frames: [symbolicatedFrame], preferOriginal: false });
    expect(screen.getByText('r')).toBeInTheDocument();
    expect(screen.getByText('dist/app.abc.js:1:42')).toBeInTheDocument();
    expect(screen.queryByText(/original/i)).not.toBeInTheDocument();
  });

  it('falls back to raw fields when a frame has no original* (mixed stack)', () => {
    const frames: RawFrame[] = [
      symbolicatedFrame,
      { filename: 'node_modules/lib.js', function: 'innerFn', lineno: 99 },
    ];
    renderView({ frames, preferOriginal: true });
    // Symbolicated frame uses originals.
    expect(screen.getByText('render')).toBeInTheDocument();
    // Raw-only frame still shows; no extra "original" badge for it.
    expect(screen.getByText('innerFn')).toBeInTheDocument();
    expect(screen.getByText('node_modules/lib.js:99')).toBeInTheDocument();
    // Only one badge (for the symbolicated frame).
    expect(screen.getAllByText(/original/i)).toHaveLength(1);
  });

  it('reverses frame order so the leaf shows first', () => {
    const frames: RawFrame[] = [
      { filename: 'top-of-stack.js', function: 'A', lineno: 1 },
      { filename: 'leaf.js', function: 'B', lineno: 2 },
    ];
    renderView({ frames, preferOriginal: false });
    const stacktrace = screen.getByTestId('stacktrace');
    const text = stacktrace.textContent ?? '';
    expect(text.indexOf('B')).toBeLessThan(text.indexOf('A'));
  });

  it('renders nothing for an empty frame list', () => {
    renderView({ frames: [], preferOriginal: false });
    expect(screen.queryByTestId('stacktrace')).not.toBeInTheDocument();
  });

  it('uses the anonymous placeholder when function is missing', () => {
    renderView({
      frames: [{ filename: 'a.js', lineno: 1 }],
      preferOriginal: false,
    });
    expect(screen.getByText('<anonymous>')).toBeInTheDocument();
  });
});
