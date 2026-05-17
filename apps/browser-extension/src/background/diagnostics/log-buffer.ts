import {
  type AuthSession,
  type CapabilitySnapshot,
  type DiagnosticBundle,
  type DiagnosticLogEntry,
  type ExtensionSettings,
} from '../../shared/validation/models';

const MAX_LOGS = 200;
const logEntries: DiagnosticLogEntry[] = [];

export function appendDiagnosticLog(entry: DiagnosticLogEntry): void {
  logEntries.push(entry);
  if (logEntries.length > MAX_LOGS) {
    logEntries.splice(0, logEntries.length - MAX_LOGS);
  }
}

export function getDiagnosticLogs(): DiagnosticLogEntry[] {
  return [...logEntries];
}

export function createDiagnosticBundle(input: {
  settings: ExtensionSettings;
  authSession: AuthSession;
  capabilitySnapshot?: CapabilitySnapshot;
}): DiagnosticBundle {
  return {
    exportedAt: new Date().toISOString(),
    settings: input.settings,
    authSession: input.authSession,
    capabilitySnapshot: input.capabilitySnapshot,
    logs: getDiagnosticLogs(),
  };
}
