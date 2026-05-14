import { spawnSync } from 'node:child_process';

/**
 * Tiny wrappers around `git` so the CLI can pre-fill release metadata without depending on a git
 * binding library. Each helper returns `null` (never throws) when:
 *   - `git` isn't on PATH
 *   - the cwd isn't a working tree
 *   - the command exits non-zero or prints nothing
 *
 * Caller (releases new --from-git) merges the results onto its own flag-supplied values, so a
 * partial result still produces a useful payload.
 */
export interface GitContext {
  sha: string | null;
  ref: string | null;
}

export function readGitContext(cwd: string = process.cwd()): GitContext {
  return {
    sha: runGit(['rev-parse', 'HEAD'], cwd),
    ref: resolveRef(cwd),
  };
}

/**
 * Lists commit subjects between two refs. Used by the auto-changelog path. Returns null if either
 * end of the range is unresolvable or the git invocation fails; the caller falls back to the
 * operator-supplied `--changelog` value (or none) in that case.
 */
export function gitLogBetween(
  fromRef: string,
  toRef: string = 'HEAD',
  cwd: string = process.cwd(),
): string | null {
  // %s is the subject line, %h the short SHA. Decorating the changelog with the SHA gives readers
  // a stable handle back to the source if they want to dig in.
  const out = runGit(['log', '--pretty=format:- %s (%h)', `${fromRef}..${toRef}`], cwd);
  if (out === null) return null;
  const trimmed = out.trim();
  return trimmed.length === 0 ? null : trimmed;
}

/**
 * Prefer a symbolic branch name; fall back to the closest tag, then `HEAD` itself. Detached HEAD
 * with no tag returns `null` so the CLI doesn't lie about the ref.
 */
function resolveRef(cwd: string): string | null {
  // `--abbrev-ref HEAD` prints `HEAD` on detached checkouts — useless. Use --symbolic-full-name
  // first to detect detached state.
  const symbolic = runGit(['symbolic-ref', '--quiet', '--short', 'HEAD'], cwd);
  if (symbolic) return symbolic;
  // Detached. Try the closest annotated tag.
  const tag = runGit(['describe', '--tags', '--exact-match', 'HEAD'], cwd);
  if (tag) return tag;
  return null;
}

function runGit(args: string[], cwd: string): string | null {
  try {
    const result = spawnSync('git', args, {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (result.error) return null;
    if (typeof result.status !== 'number' || result.status !== 0) return null;
    const out = (result.stdout ?? '').trim();
    return out.length === 0 ? null : out;
  } catch {
    return null;
  }
}
