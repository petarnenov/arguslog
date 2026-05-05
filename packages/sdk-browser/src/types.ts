export type Level = 'fatal' | 'error' | 'warning' | 'info' | 'debug';

export interface User {
  id?: string;
  email?: string;
  username?: string;
}

export interface Breadcrumb {
  timestamp: number;
  category: string;
  message: string;
  level: Level;
  data?: Record<string, unknown>;
}

export interface StackFrame {
  filename?: string;
  function?: string;
  lineno?: number;
  colno?: number;
  inApp?: boolean;
}

export interface ExceptionPayload {
  type: string;
  value: string;
  stacktrace?: { frames: StackFrame[] };
}

export interface EventPayload {
  eventId: string;
  timestamp: number;
  platform: 'javascript';
  sdk: { name: string; version: string };
  release?: string;
  environment?: string;
  level: Level;
  message?: string;
  exception?: { values: ExceptionPayload[] };
  user?: User;
  tags?: Record<string, string>;
  contexts?: Record<string, Record<string, unknown>>;
  breadcrumbs?: Breadcrumb[];
  request?: { url?: string; userAgent?: string };
}

export type BeforeSend = (event: EventPayload) => EventPayload | null | Promise<EventPayload | null>;

export interface ArgusOptions {
  dsn: string;
  release?: string;
  environment?: string;
  sampleRate?: number;
  maxBreadcrumbs?: number;
  beforeSend?: BeforeSend;
  scrubbing?: { enabled?: boolean; extraPatterns?: RegExp[] };
  transport?: { fetch?: typeof fetch; maxRetries?: number };
  integrations?: ('globalHandlers' | 'breadcrumbs')[];
  debug?: boolean;
}

export interface ParsedDsn {
  publicKey: string;
  host: string;
  protocol: 'http' | 'https';
  projectId: string;
  ingestUrl: string;
}
