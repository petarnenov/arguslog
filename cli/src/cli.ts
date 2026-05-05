import { VERSION } from './version.js';

export interface CommandResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}

const HELP = `argus ${VERSION}

Usage:
  argus <command> [options]

Commands:
  releases new <version>          Create a new release  (P3)
  sourcemaps upload <path>        Upload sourcemaps     (P3)
  help                            Show this help
  version                         Print CLI version

This is a P0 placeholder. Real commands ship in P3 alongside the alerts +
symbolication milestone. See https://github.com/petarnenov/argus for status.
`;

export function parseArgs(argv: readonly string[]): { command: string; rest: readonly string[] } {
  const [command = 'help', ...rest] = argv;
  return { command, rest };
}

export function run(argv: readonly string[]): CommandResult {
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
    case 'releases':
    case 'sourcemaps':
      return {
        exitCode: 2,
        stdout: '',
        stderr: `argus: '${[command, ...rest].join(' ').trim()}' — not implemented yet (lands in P3)\n`,
      };
    default:
      return {
        exitCode: 1,
        stdout: '',
        stderr: `argus: unknown command '${command}'. Run 'argus help' for usage.\n`,
      };
  }
}
