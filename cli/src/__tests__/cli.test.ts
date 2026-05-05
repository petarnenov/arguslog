import { describe, expect, it } from 'vitest';

import { parseArgs, run } from '../cli.js';
import { VERSION } from '../version.js';

describe('parseArgs', () => {
  it('defaults to help when no command given', () => {
    expect(parseArgs([])).toEqual({ command: 'help', rest: [] });
  });

  it('splits command from rest', () => {
    expect(parseArgs(['releases', 'new', '1.2.3'])).toEqual({
      command: 'releases',
      rest: ['new', '1.2.3'],
    });
  });
});

describe('run', () => {
  it.each([['help'], ['--help'], ['-h']])('prints usage on %s', (flag) => {
    const r = run([flag]);
    expect(r.exitCode).toBe(0);
    expect(r.stdout).toContain('Usage:');
    expect(r.stderr).toBe('');
  });

  it.each([['version'], ['--version'], ['-v']])('prints VERSION on %s', (flag) => {
    const r = run([flag]);
    expect(r.exitCode).toBe(0);
    expect(r.stdout.trim()).toBe(VERSION);
  });

  it('returns exit 2 for stub releases/sourcemaps subcommands', () => {
    expect(run(['releases', 'new', '1.0.0']).exitCode).toBe(2);
    expect(run(['sourcemaps', 'upload', './dist']).exitCode).toBe(2);
  });

  it('returns exit 1 for unknown command', () => {
    const r = run(['bogus']);
    expect(r.exitCode).toBe(1);
    expect(r.stderr).toContain('unknown command');
  });

  it('defaults to help when argv is empty', () => {
    expect(run([]).exitCode).toBe(0);
  });
});
