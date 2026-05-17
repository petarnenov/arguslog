import {
  type AlertLevel,
  type AlertRule,
  alertsCreate,
  alertsDelete,
  alertsGet,
  alertsList,
  alertsUpdate,
  composeBody,
  parseDestinationIds,
  parseLevels,
  parseTagValuesFlag,
  parseWindowShorthand,
} from './commands/alerts.js';
import { ping } from './commands/ping.js';
import {
  type Release,
  releasesDelete,
  releasesGet,
  releasesList,
  releasesNew,
  releasesUpdate,
} from './commands/releases.js';
import { sourcemapsUpload } from './commands/sourcemaps.js';
import { gitLogBetween, readGitContext } from './lib/git.js';
import { type CliConfig, CliConfigError, loadConfig } from './config.js';
import { parseFlags } from './flags.js';
import { ApiError } from './http.js';
import { VERSION } from './version.generated.js';

export interface CommandResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}

export interface RunOptions {
  /** Override config loading — useful for tests; production reads from env + ~/.arguslog. */
  loadConfig?: () => CliConfig;
}

const HELP = `arguslog ${VERSION}

Usage:
  arguslog <command> [options]

Commands:
  ping --project <id>             Send a synthetic event through ingest to verify
                                  the project's SDK wire path end-to-end.
  releases new <version> --project <id>
                                  [--released-at <iso>] [--git-sha <sha>]
                                  [--git-ref <ref>] [--deploy-stage <stage>]
                                  [--changelog <text>] [--from-git]
                                  [--changelog-from-git <prev-ref>]
                                  Create a new release tag with optional metadata.
                                  --from-git auto-fills git-sha + git-ref from
                                  the working tree (explicit flags win).
                                  --changelog-from-git <ref> runs
                                  git log <ref>..HEAD and pre-fills
                                  changelog with one bullet per commit.
  releases list --project <id>    List releases for a project (newest first).
  releases get <releaseId> --project <id>
                                  Fetch full metadata for one release.
  releases update <releaseId> --project <id>
                                  [--version <v>] [--released-at <iso>]
                                  [--git-sha <sha>] [--git-ref <ref>]
                                  [--deploy-stage <stage>] [--changelog <text>]
                                  Edit a release. Omit a flag to keep the
                                  current value; pass an empty string to clear.
  releases delete <releaseId> --project <id>
                                  [--yes]
                                  Delete a release (and all attached source
                                  maps). Pass --yes to skip the confirmation.
  sourcemaps upload <path> --project <id> --release <id> [--name <originalPath>]
                                  Upload a sourcemap and attach it to a release.
  alerts list --project <id>      List alert rules for a project.
  alerts get <ruleId> --project <id>
                                  Fetch full detail for one alert rule.
  alerts create --project <id> --name <s> --destination <id> [--destination <id> ...]
                                  [--level error,fatal] [--window 5m|2h|1d|<iso>]
                                  [--threshold N] [--tag-key <s> --tag-values v1,v2]
                                  [--throttle <seconds>] [--disabled]
                                  Create an alert rule. --level / --window /
                                  --threshold are optional (omit = match-all on
                                  that clause); --tag-key requires --tag-values
                                  and vice versa.
  alerts update <ruleId> --project <id> [flags as above]
                                  Edit a rule. Omit a flag to keep its current
                                  value (full-PUT against the existing row).
  alerts delete <ruleId> --project <id> [--yes]
                                  Delete a rule. Pass --yes to skip confirmation.
  help                            Show this help.
  version                         Print CLI version.

Auth:
  Token comes from $ARGUSLOG_TOKEN or ~/.arguslog/credentials
    (JSON: { "token": "arglog_pat_...", "apiBaseUrl": "https://..." }).
  $ARGUSLOG_API_URL overrides the api base URL when present.
  $ARGUSLOG_INGEST_URL overrides the ingest base URL used by 'ping'
    (default: derived from $ARGUSLOG_API_URL by swapping api.* → ingest.*).
`;

export function parseArgs(argv: readonly string[]): { command: string; rest: readonly string[] } {
  const [command = 'help', ...rest] = argv;
  return { command, rest };
}

export async function run(
  argv: readonly string[],
  options: RunOptions = {},
): Promise<CommandResult> {
  const { command, rest } = parseArgs(argv);

  switch (command) {
    case 'help':
    case '--help':
    case '-h':
      return { exitCode: 0, stdout: HELP, stderr: '' };
    case 'version':
    case '--version':
    case '-v':
      return { exitCode: 0, stdout: `${VERSION}\n`, stderr: '' };
    case 'ping':
      return runPing(rest, options);
    case 'releases':
      return runReleases(rest, options);
    case 'sourcemaps':
      return runSourcemaps(rest, options);
    case 'alerts':
      return runAlerts(rest, options);
    default:
      return {
        exitCode: 1,
        stdout: '',
        stderr: `arguslog: unknown command '${command}'. Run 'arguslog help' for usage.\n`,
      };
  }
}

async function runPing(rest: readonly string[], options: RunOptions): Promise<CommandResult> {
  const { flags } = parseFlags(rest);
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('ping: --project <id> is required (numeric).');
  }
  const ingestUrlOverride = process.env.ARGUSLOG_INGEST_URL?.trim() || undefined;
  return withConfig(options, async (config) => {
    const result = await ping({ projectId, ingestUrlOverride }, config);
    return ok(
      `✓ ingest accepted synthetic event ${result.eventId.slice(0, 12)}…\n` +
        `  via ${result.ingestUrl} (DSN ${result.dsnPublic.slice(0, 8)}…)\n` +
        `  check the Issues page in ~1s — search 'synthetic=true' to find it.\n`,
    );
  });
}

async function runReleases(rest: readonly string[], options: RunOptions): Promise<CommandResult> {
  const [sub, ...subRest] = rest;
  switch (sub) {
    case 'new':
      return runReleasesNew(subRest, options);
    case 'list':
      return runReleasesList(subRest, options);
    case 'get':
      return runReleasesGet(subRest, options);
    case 'update':
      return runReleasesUpdate(subRest, options);
    case 'delete':
      return runReleasesDelete(subRest, options);
    default:
      return usageError(
        `releases: unknown subcommand '${sub ?? ''}'. Try one of: new, list, get, update, delete.`,
      );
  }
}

async function runReleasesNew(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { positional, flags } = parseFlags(rest);
  const version = positional[0];
  if (!version) return usageError('releases new: missing <version> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) return usageError('releases new: --project <id> is required (numeric).');

  // --from-git auto-fills git-sha + git-ref from the working tree. Explicit flags always win —
  // CI pipelines that already know their SHA shouldn't be overridden by whatever happens to be
  // checked out on the build agent.
  let gitSha = optional(flags['git-sha']);
  let gitRef = optional(flags['git-ref']);
  if (flags['from-git'] !== undefined) {
    const ctx = readGitContext();
    if (gitSha === undefined && ctx.sha !== null) gitSha = ctx.sha;
    if (gitRef === undefined && ctx.ref !== null) gitRef = ctx.ref;
  }

  // --changelog-from-git <prev-ref> runs `git log prev..HEAD` and uses the bullet list as the
  // changelog. Explicit --changelog still wins so the operator can override the auto-derive
  // (e.g. paste a human-written summary even when their CI usually populates from git).
  let changelog = optional(flags.changelog);
  const changelogFromRef = optional(flags['changelog-from-git']);
  if (changelog === undefined && changelogFromRef !== undefined) {
    const derived = gitLogBetween(changelogFromRef);
    if (derived !== null) changelog = derived;
  }

  return withConfig(options, async (config) => {
    const release = await releasesNew(
      {
        version,
        projectId,
        releasedAt: optional(flags['released-at']),
        gitSha,
        gitRef,
        deployStage: optional(flags['deploy-stage']),
        changelog,
      },
      config,
    );
    return ok(`release #${release.id} created: ${release.version}\n`);
  });
}

async function runReleasesList(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { flags } = parseFlags(rest);
  const projectId = parseProjectId(flags.project);
  if (projectId === null) return usageError('releases list: --project <id> is required (numeric).');

  return withConfig(options, async (config) => {
    const releases = await releasesList(projectId, config);
    if (releases.length === 0) {
      return ok('no releases yet.\n');
    }
    return ok(releases.map(formatReleaseLine).join('') + `\n${releases.length} release(s).\n`);
  });
}

async function runReleasesGet(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { positional, flags } = parseFlags(rest);
  const releaseId = parseProjectId(positional[0]);
  if (releaseId === null) return usageError('releases get: missing <releaseId> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) return usageError('releases get: --project <id> is required (numeric).');

  return withConfig(options, async (config) => {
    const release = await releasesGet(projectId, releaseId, config);
    return ok(formatReleaseDetail(release));
  });
}

async function runReleasesUpdate(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { positional, flags } = parseFlags(rest);
  const releaseId = parseProjectId(positional[0]);
  if (releaseId === null) return usageError('releases update: missing <releaseId> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('releases update: --project <id> is required (numeric).');
  }
  // At least one mutating flag must be present, otherwise the command is a no-op.
  const editable = [
    flags.version,
    flags['released-at'],
    flags['git-sha'],
    flags['git-ref'],
    flags['deploy-stage'],
    flags.changelog,
  ];
  if (editable.every((v) => v === undefined)) {
    return usageError(
      'releases update: pass at least one of --version / --released-at / --git-sha / --git-ref / --deploy-stage / --changelog.',
    );
  }

  return withConfig(options, async (config) => {
    const release = await releasesUpdate(
      {
        projectId,
        releaseId,
        version: optional(flags.version),
        releasedAt: optional(flags['released-at']),
        gitSha: optional(flags['git-sha']),
        gitRef: optional(flags['git-ref']),
        deployStage: optional(flags['deploy-stage']),
        changelog: optional(flags.changelog),
      },
      config,
    );
    return ok(`release #${release.id} updated: ${release.version}\n`);
  });
}

async function runReleasesDelete(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { positional, flags } = parseFlags(rest);
  const releaseId = parseProjectId(positional[0]);
  if (releaseId === null) return usageError('releases delete: missing <releaseId> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('releases delete: --project <id> is required (numeric).');
  }
  if (flags.yes === undefined && flags.y === undefined) {
    return usageError(
      'releases delete: pass --yes to confirm (this drops the release and all attached source maps).',
    );
  }

  return withConfig(options, async (config) => {
    await releasesDelete(projectId, releaseId, config);
    return ok(`release #${releaseId} deleted.\n`);
  });
}

// `parseFlags` returns flags as `string | undefined`; CLI consumers sometimes want to forward an
// explicitly-empty string (to clear a field on update), so we keep "" intact and only convert
// trimmed-away whitespace.
function optional(raw: string | undefined): string | undefined {
  if (raw === undefined) return undefined;
  return raw;
}

function formatReleaseLine(r: Release): string {
  const stage = r.deployStage ? ` [${r.deployStage}]` : '';
  const sha = r.gitSha ? ` ${r.gitSha.slice(0, 7)}` : '';
  return `#${r.id}\t${r.version}${stage}${sha}\t${r.createdAt}\n`;
}

function formatReleaseDetail(r: Release): string {
  const lines = [
    `id:           ${r.id}`,
    `version:      ${r.version}`,
    `createdAt:    ${r.createdAt}`,
    `releasedAt:   ${r.releasedAt ?? '-'}`,
    `gitRef:       ${r.gitRef ?? '-'}`,
    `gitSha:       ${r.gitSha ?? '-'}`,
    `deployStage:  ${r.deployStage ?? '-'}`,
  ];
  if (r.changelog) {
    lines.push('changelog:');
    for (const line of r.changelog.split('\n')) lines.push(`  ${line}`);
  } else {
    lines.push('changelog:    -');
  }
  return lines.join('\n') + '\n';
}

async function runSourcemaps(rest: readonly string[], options: RunOptions): Promise<CommandResult> {
  const [sub, ...subRest] = rest;
  if (sub !== 'upload') {
    return usageError(
      `sourcemaps: unknown subcommand '${sub ?? ''}'. Try 'sourcemaps upload <path>'.`,
    );
  }
  const { positional, flags } = parseFlags(subRest);
  const filePath = positional[0];
  if (!filePath) return usageError('sourcemaps upload: missing <path> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('sourcemaps upload: --project <id> is required (numeric).');
  }
  const releaseId = parseProjectId(flags.release);
  if (releaseId === null) {
    return usageError('sourcemaps upload: --release <id> is required (numeric).');
  }
  // Default the on-disk filename as the originalPath unless the caller renames it (lets a CI
  // pipeline upload `dist/app.abc123.js.map` but record it as `dist/app.js`).
  const originalPath = flags.name?.trim() || basenameOf(filePath);

  return withConfig(options, async (config) => {
    const result = await sourcemapsUpload({ filePath, originalPath, projectId, releaseId }, config);
    return ok(
      `sourcemap #${result.artifact.id} uploaded (${result.artifact.sizeBytes} bytes, ` +
        `sha256=${result.artifact.sha256.slice(0, 12)}…)\n`,
    );
  });
}

async function runAlerts(rest: readonly string[], options: RunOptions): Promise<CommandResult> {
  const [sub, ...subRest] = rest;
  switch (sub) {
    case 'list':
      return runAlertsList(subRest, options);
    case 'get':
      return runAlertsGet(subRest, options);
    case 'create':
      return runAlertsCreate(subRest, options);
    case 'update':
      return runAlertsUpdate(subRest, options);
    case 'delete':
      return runAlertsDelete(subRest, options);
    default:
      return usageError(
        `alerts: unknown subcommand '${sub ?? ''}'. Try one of: list, get, create, update, delete.`,
      );
  }
}

async function runAlertsList(rest: readonly string[], options: RunOptions): Promise<CommandResult> {
  const { flags } = parseFlags(rest);
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('alerts list: --project <id> is required (numeric).');
  }
  return withConfig(options, async (config) => {
    const rules = await alertsList(projectId, config);
    if (rules.length === 0) return ok('no alert rules yet.\n');
    return ok(rules.map(formatAlertRuleLine).join('') + `\n${rules.length} rule(s).\n`);
  });
}

async function runAlertsGet(rest: readonly string[], options: RunOptions): Promise<CommandResult> {
  const { positional, flags } = parseFlags(rest);
  const ruleId = parseProjectId(positional[0]);
  if (ruleId === null) return usageError('alerts get: missing <ruleId> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) return usageError('alerts get: --project <id> is required (numeric).');

  return withConfig(options, async (config) => {
    const rule = await alertsGet(projectId, ruleId, config);
    return ok(formatAlertRuleDetail(rule));
  });
}

interface AlertFlagDescriptor {
  levels?: AlertLevel[];
  firstSeenWindow?: string;
  occurrenceThreshold?: number;
  tagKey?: string;
  tagValues?: string[];
  destinationIds?: number[];
  throttleSeconds?: number;
  enabled?: boolean;
}

/** Parses the flags shared by create + update. Returns either a populated descriptor or a usage error. */
function parseAlertFlags(
  flags: Record<string, string>,
): { ok: true; descriptor: AlertFlagDescriptor } | { ok: false; message: string } {
  let levels: AlertLevel[] | undefined;
  if (flags.level !== undefined) {
    const parsed = parseLevels(flags.level);
    if (parsed === null) {
      return {
        ok: false,
        message: '--level expects a comma-separated subset of fatal,error,warning,info,debug.',
      };
    }
    levels = parsed;
  }

  let firstSeenWindow: string | undefined;
  if (flags.window !== undefined) {
    const parsed = parseWindowShorthand(flags.window);
    if (parsed === null) {
      return {
        ok: false,
        message: '--window expects "5m" / "2h" / "1d" shorthand or a raw ISO-8601 duration.',
      };
    }
    firstSeenWindow = parsed;
  }

  let occurrenceThreshold: number | undefined;
  if (flags.threshold !== undefined) {
    const n = Number(flags.threshold);
    if (!Number.isInteger(n) || n < 1) {
      return { ok: false, message: '--threshold expects a positive integer.' };
    }
    occurrenceThreshold = n;
  }

  const hasTagKey = flags['tag-key'] !== undefined;
  const hasTagValues = flags['tag-values'] !== undefined;
  if (hasTagKey !== hasTagValues) {
    return {
      ok: false,
      message: '--tag-key and --tag-values must be supplied together (or omitted together).',
    };
  }
  let tagKey: string | undefined;
  let tagValues: string[] | undefined;
  if (hasTagKey && hasTagValues) {
    tagKey = (flags['tag-key'] ?? '').trim();
    tagValues = parseTagValuesFlag(flags['tag-values'] ?? '');
    if (!tagKey || tagValues.length === 0) {
      return {
        ok: false,
        message: '--tag-key and --tag-values must each carry non-empty values.',
      };
    }
  }

  let destinationIds: number[] | undefined;
  if (flags.destination !== undefined) {
    // parseFlags collapses repeated --flag values to the last one, so we expect users to pass
    // comma-separated ids (the README documents this).
    const ids = parseDestinationIds([flags.destination]);
    if (ids === null) {
      return {
        ok: false,
        message: '--destination expects one or more positive integer ids (comma-separated).',
      };
    }
    destinationIds = ids;
  }

  let throttleSeconds: number | undefined;
  if (flags.throttle !== undefined) {
    const n = Number(flags.throttle);
    if (!Number.isInteger(n) || n < 0) {
      return { ok: false, message: '--throttle expects a non-negative integer (seconds).' };
    }
    throttleSeconds = n;
  }

  const enabled = flags.disabled !== undefined ? false : undefined;

  return {
    ok: true,
    descriptor: {
      levels,
      firstSeenWindow,
      occurrenceThreshold,
      tagKey,
      tagValues,
      destinationIds,
      throttleSeconds,
      enabled,
    },
  };
}

async function runAlertsCreate(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { flags } = parseFlags(rest);
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('alerts create: --project <id> is required (numeric).');
  }
  const name = optional(flags.name);
  if (!name || !name.trim()) {
    return usageError('alerts create: --name <s> is required.');
  }
  const parsed = parseAlertFlags(flags);
  if (!parsed.ok) return usageError(`alerts create: ${parsed.message}`);
  if (!parsed.descriptor.destinationIds || parsed.descriptor.destinationIds.length === 0) {
    return usageError(
      'alerts create: at least one --destination <id> is required so the rule has somewhere to fire.',
    );
  }

  return withConfig(options, async (config) => {
    const body = composeBody({
      name: name.trim(),
      levels: parsed.descriptor.levels,
      firstSeenWindow: parsed.descriptor.firstSeenWindow,
      occurrenceThreshold: parsed.descriptor.occurrenceThreshold,
      tagKey: parsed.descriptor.tagKey,
      tagValues: parsed.descriptor.tagValues,
      destinationIds: parsed.descriptor.destinationIds,
      throttleSeconds: parsed.descriptor.throttleSeconds,
      enabled: parsed.descriptor.enabled,
    });
    const rule = await alertsCreate(projectId, body, config);
    return ok(`rule #${rule.id} created: ${rule.name}\n`);
  });
}

async function runAlertsUpdate(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { positional, flags } = parseFlags(rest);
  const ruleId = parseProjectId(positional[0]);
  if (ruleId === null) return usageError('alerts update: missing <ruleId> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('alerts update: --project <id> is required (numeric).');
  }
  const parsed = parseAlertFlags(flags);
  if (!parsed.ok) return usageError(`alerts update: ${parsed.message}`);

  return withConfig(options, async (config) => {
    const current = await alertsGet(projectId, ruleId, config);
    const body = composeBody({
      base: current,
      name: optional(flags.name),
      levels: parsed.descriptor.levels,
      firstSeenWindow: parsed.descriptor.firstSeenWindow,
      occurrenceThreshold: parsed.descriptor.occurrenceThreshold,
      tagKey: parsed.descriptor.tagKey,
      tagValues: parsed.descriptor.tagValues,
      destinationIds: parsed.descriptor.destinationIds,
      throttleSeconds: parsed.descriptor.throttleSeconds,
      enabled: parsed.descriptor.enabled,
    });
    const rule = await alertsUpdate(projectId, ruleId, body, config);
    return ok(`rule #${rule.id} updated: ${rule.name}\n`);
  });
}

async function runAlertsDelete(
  rest: readonly string[],
  options: RunOptions,
): Promise<CommandResult> {
  const { positional, flags } = parseFlags(rest);
  const ruleId = parseProjectId(positional[0]);
  if (ruleId === null) return usageError('alerts delete: missing <ruleId> argument.');
  const projectId = parseProjectId(flags.project);
  if (projectId === null) {
    return usageError('alerts delete: --project <id> is required (numeric).');
  }
  if (flags.yes === undefined && flags.y === undefined) {
    return usageError('alerts delete: pass --yes to confirm.');
  }
  return withConfig(options, async (config) => {
    await alertsDelete(projectId, ruleId, config);
    return ok(`rule #${ruleId} deleted.\n`);
  });
}

function formatAlertRuleLine(r: AlertRule): string {
  const dest = r.actions.destinationIds.length;
  const status = r.enabled ? '' : ' [disabled]';
  return `#${r.id}\t${r.name}\t${dest} dest\t${r.throttleSeconds}s${status}\n`;
}

function formatAlertRuleDetail(r: AlertRule): string {
  const lines = [
    `id:                  ${r.id}`,
    `name:                ${r.name}`,
    `projectId:           ${r.projectId}`,
    `enabled:             ${r.enabled}`,
    `throttleSeconds:     ${r.throttleSeconds}`,
    `level.in:            ${r.conditions.level?.in?.join(', ') ?? '-'}`,
    `firstSeenWindow:     ${r.conditions.firstSeenWindow ?? '-'}`,
    `occurrenceThreshold: ${r.conditions.occurrenceThreshold ?? '-'}`,
    `tag.key:             ${r.conditions.tag?.key ?? '-'}`,
    `tag.in:              ${r.conditions.tag?.in?.join(', ') ?? '-'}`,
    `destinationIds:      ${r.actions.destinationIds.join(', ')}`,
    `createdAt:           ${r.createdAt}`,
  ];
  return lines.join('\n') + '\n';
}

async function withConfig(
  options: RunOptions,
  body: (config: CliConfig) => Promise<CommandResult>,
): Promise<CommandResult> {
  let config: CliConfig;
  try {
    config = (options.loadConfig ?? loadConfig)();
  } catch (err) {
    if (err instanceof CliConfigError) return fail(`arguslog: ${err.message}\n`);
    throw err;
  }
  try {
    return await body(config);
  } catch (err) {
    if (err instanceof ApiError) {
      return fail(`arguslog: api ${err.status} — ${err.problem.detail ?? err.problem.title}\n`);
    }
    if (err instanceof Error) return fail(`arguslog: ${err.message}\n`);
    return fail(`arguslog: ${String(err)}\n`);
  }
}

function parseProjectId(raw: string | undefined): number | null {
  if (raw === undefined) return null;
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : null;
}

function basenameOf(p: string): string {
  const slash = p.lastIndexOf('/');
  return slash >= 0 ? p.slice(slash + 1) : p;
}

function ok(line: string): CommandResult {
  return { exitCode: 0, stdout: line, stderr: '' };
}

function usageError(message: string): CommandResult {
  return { exitCode: 2, stdout: '', stderr: `arguslog: ${message}\n` };
}

function fail(message: string): CommandResult {
  return { exitCode: 1, stdout: '', stderr: message };
}
