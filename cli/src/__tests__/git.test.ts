import { execSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { gitLogBetween, readGitContext } from '../lib/git.js';

/**
 * Spins up a throw-away git repo, makes a commit on a known branch + tag, and asserts the
 * helpers surface the values that `arguslog releases new --from-git` would forward to the API.
 * Skips if `git` isn't on PATH — we don't fail the suite on machines without it.
 */
function isGitAvailable(): boolean {
  try {
    execSync('git --version', { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

describe.skipIf(!isGitAvailable())('readGitContext', () => {
  let repo: string;

  beforeEach(() => {
    repo = mkdtempSync(join(tmpdir(), 'arguslog-git-'));
    execSync('git init -q -b main', { cwd: repo });
    execSync('git config user.email "ci@arguslog.test"', { cwd: repo });
    execSync('git config user.name "Arguslog CI"', { cwd: repo });
    writeFileSync(join(repo, 'README.md'), 'hello\n');
    execSync('git add README.md', { cwd: repo });
    execSync('git commit -q -m "initial"', { cwd: repo });
  });

  afterEach(() => {
    rmSync(repo, { recursive: true, force: true });
  });

  it('returns the branch ref and full HEAD sha on a clean main', () => {
    const ctx = readGitContext(repo);
    expect(ctx.ref).toBe('main');
    expect(ctx.sha).toMatch(/^[0-9a-f]{40}$/);
  });

  it('falls back to the closest exact tag on a detached HEAD', () => {
    const head = execSync('git rev-parse HEAD', { cwd: repo, encoding: 'utf8' }).trim();
    execSync('git tag v9.0.0', { cwd: repo });
    execSync(`git checkout -q --detach ${head}`, { cwd: repo });

    const ctx = readGitContext(repo);
    expect(ctx.ref).toBe('v9.0.0');
    expect(ctx.sha).toBe(head);
  });

  it('returns null ref on detached HEAD without an exact tag', () => {
    const head = execSync('git rev-parse HEAD', { cwd: repo, encoding: 'utf8' }).trim();
    execSync(`git checkout -q --detach ${head}`, { cwd: repo });

    const ctx = readGitContext(repo);
    expect(ctx.ref).toBeNull();
    expect(ctx.sha).toBe(head);
  });

  it('returns both fields null when the cwd is not a git repo', () => {
    const tmp = mkdtempSync(join(tmpdir(), 'arguslog-nongit-'));
    try {
      const ctx = readGitContext(tmp);
      expect(ctx.sha).toBeNull();
      expect(ctx.ref).toBeNull();
    } finally {
      rmSync(tmp, { recursive: true, force: true });
    }
  });
});

describe.skipIf(!isGitAvailable())('gitLogBetween', () => {
  let repo: string;

  beforeEach(() => {
    repo = mkdtempSync(join(tmpdir(), 'arguslog-gitlog-'));
    execSync('git init -q -b main', { cwd: repo });
    execSync('git config user.email "ci@arguslog.test"', { cwd: repo });
    execSync('git config user.name "Arguslog CI"', { cwd: repo });
    writeFileSync(join(repo, 'a.txt'), 'a\n');
    execSync('git add a.txt && git commit -q -m "initial"', { cwd: repo });
    execSync('git tag v1.0.0', { cwd: repo });
    writeFileSync(join(repo, 'b.txt'), 'b\n');
    execSync('git add b.txt && git commit -q -m "ship: widget"', { cwd: repo });
    writeFileSync(join(repo, 'c.txt'), 'c\n');
    execSync('git add c.txt && git commit -q -m "fix: edge case"', { cwd: repo });
  });

  afterEach(() => {
    rmSync(repo, { recursive: true, force: true });
  });

  it('returns one bullet per commit between two refs', () => {
    const log = gitLogBetween('v1.0.0', 'HEAD', repo);
    expect(log).not.toBeNull();
    expect(log).toContain('ship: widget');
    expect(log).toContain('fix: edge case');
    expect(log!.startsWith('- ')).toBe(true);
  });

  it('returns null when the range yields no commits', () => {
    const log = gitLogBetween('HEAD', 'HEAD', repo);
    expect(log).toBeNull();
  });

  it('returns null when a ref is unresolvable', () => {
    const log = gitLogBetween('does-not-exist', 'HEAD', repo);
    expect(log).toBeNull();
  });
});
