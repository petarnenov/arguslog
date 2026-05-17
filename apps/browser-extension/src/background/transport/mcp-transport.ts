import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import {
  StreamableHTTPClientTransport,
  StreamableHTTPError,
} from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js';
import { CfWorkerJsonSchemaValidator } from '@modelcontextprotocol/sdk/validation/cfworker';
import { z } from 'zod';

import {
  McpHealthSchema,
  McpPromptResultSchema,
  McpPromptSchema,
  McpToolCallResultSchema,
  McpToolSchema,
  type McpPromptDefinition,
  type McpToolDefinition,
} from '../../shared/mcp/protocol';
import { createAppError, type AppError } from '../../shared/types/errors';
import {
  AccountSummarySchema,
  type AccountSummary,
  type CapabilitySnapshot,
} from '../../shared/validation/models';
import { appendDiagnosticLog } from '../diagnostics/log-buffer';

interface TransportConfig {
  endpoint: string;
  pat: string;
}

const jsonSchemaValidator = new CfWorkerJsonSchemaValidator();

function endpointBase(endpoint: string): string {
  return endpoint.endsWith('/mcp') ? endpoint.slice(0, -4) : endpoint;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function readRetryDelay(attempt: number): number {
  const base = 300 * 2 ** attempt;
  return Math.min(2_000, base + Math.floor(Math.random() * 150));
}

function parseRetryAfter(headers: Headers): number | undefined {
  const retryAfter = headers.get('retry-after');
  if (!retryAfter) return undefined;
  const seconds = Number(retryAfter);
  return Number.isFinite(seconds) ? seconds * 1000 : undefined;
}

function mapTransportError(error: unknown): AppError {
  if (error instanceof StreamableHTTPError) {
    if (error.code === 401) {
      return createAppError('INVALID_PAT', error.message || 'Invalid PAT.', { status: 401 });
    }
    if (error.code === 403) {
      return createAppError('INSUFFICIENT_SCOPE', error.message || 'Insufficient scope.', {
        status: 403,
      });
    }
    if (error.code === 429) {
      return createAppError('THROTTLED', error.message || 'Rate limited.', { status: 429 });
    }
    return createAppError(
      'SERVER_UNAVAILABLE',
      error.message || 'The MCP endpoint is unavailable.',
      { status: error.code },
    );
  }

  if (error instanceof Error) {
    const message = error.message;
    if (message.includes('401')) {
      return createAppError('INVALID_PAT', message, { status: 401 });
    }
    if (message.includes('403')) {
      return createAppError('INSUFFICIENT_SCOPE', message, { status: 403 });
    }
    if (message.includes('429') || /rate limit/i.test(message)) {
      return createAppError('THROTTLED', message, { status: 429 });
    }
    return createAppError('SERVER_UNAVAILABLE', message);
  }

  return createAppError('SERVER_UNAVAILABLE', 'Unexpected transport failure.');
}

function mapToolError(name: string, text: string): AppError {
  if (/401/.test(text)) {
    return createAppError('INVALID_PAT', text, { status: 401 });
  }
  if (/403/.test(text)) {
    return createAppError('INSUFFICIENT_SCOPE', text, { status: 403 });
  }
  if (/429/.test(text) || /rate limit/i.test(text)) {
    return createAppError('THROTTLED', text, { status: 429 });
  }
  if (/unknown tool/i.test(text)) {
    return createAppError('TOOL_MISSING', `Tool "${name}" is not available on the server.`);
  }
  return createAppError('SERVER_UNAVAILABLE', text);
}

function normalizeToolData(result: {
  structuredContent?: Record<string, unknown> | undefined;
  content: Array<{ text: string }>;
}): unknown {
  if (result.structuredContent) {
    const wrapped = result.structuredContent;
    if (
      'result' in wrapped &&
      wrapped.result &&
      (Array.isArray(wrapped.result) || typeof wrapped.result === 'object')
    ) {
      return wrapped.result;
    }
    return wrapped;
  }

  const text = result.content[0]?.text;
  if (!text) return null;

  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function withClient<T>(
  config: TransportConfig,
  op: string,
  run: (client: Client) => Promise<T>,
): Promise<T> {
  const startedAt = Date.now();
  const transport = new StreamableHTTPClientTransport(new URL(config.endpoint), {
    requestInit: {
      headers: {
        Authorization: `Bearer ${config.pat}`,
      },
    },
  });
  const client = new Client(
    {
      name: 'arguslog-browser-extension',
      version: '0.1.0',
    },
    {
      jsonSchemaValidator,
    },
  );

  try {
    // The SDK transport types are compiled without exactOptionalPropertyTypes and need a narrow cast here.
    await client.connect(transport as unknown as Transport);
    const result = await run(client);
    appendDiagnosticLog({
      ts: new Date().toISOString(),
      op,
      durationMs: Date.now() - startedAt,
      outcome: 'ok',
    });
    return result;
  } catch (error) {
    const appError = mapTransportError(error);
    appendDiagnosticLog({
      ts: new Date().toISOString(),
      op,
      durationMs: Date.now() - startedAt,
      outcome: 'error',
      errorBucket: appError.bucket,
      meta: appError.details,
    });
    throw appError;
  } finally {
    await transport.close().catch(() => undefined);
  }
}

async function withReadRetry<T>(op: string, fn: () => Promise<T>): Promise<T> {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return await fn();
    } catch (error) {
      const appError = error as AppError;
      if (
        attempt === 2 ||
        appError.bucket === 'INVALID_PAT' ||
        appError.bucket === 'INSUFFICIENT_SCOPE' ||
        appError.bucket === 'SCHEMA_DRIFT'
      ) {
        throw error;
      }
      const delay = appError.retryAfterMs ?? readRetryDelay(attempt);
      await sleep(delay);
    }
  }
  throw createAppError('SERVER_UNAVAILABLE', `Read operation "${op}" exhausted retries.`);
}

export async function getHealth(endpoint: string): Promise<{ version: string }> {
  const response = await fetch(`${endpointBase(endpoint)}/healthz`);
  if (!response.ok) {
    throw createAppError('SERVER_UNAVAILABLE', 'Health check failed.', {
      status: response.status,
      retryAfterMs: parseRetryAfter(response.headers),
    });
  }
  const payload = McpHealthSchema.safeParse(await response.json());
  if (!payload.success) {
    throw createAppError('SCHEMA_DRIFT', 'Unexpected /healthz response.', {
      details: payload.error.flatten(),
    });
  }
  return { version: payload.data.version };
}

export async function listTools(config: TransportConfig): Promise<McpToolDefinition[]> {
  return withReadRetry('catalog/tools', () =>
    withClient(config, 'catalog/tools', async (client) => {
      const response = await client.listTools();
      return z.array(McpToolSchema).parse(response.tools);
    }),
  );
}

export async function listPrompts(config: TransportConfig): Promise<McpPromptDefinition[]> {
  return withReadRetry('catalog/prompts', () =>
    withClient(config, 'catalog/prompts', async (client) => {
      const response = await client.listPrompts();
      return z.array(McpPromptSchema).parse(response.prompts);
    }),
  );
}

export async function getPrompt(
  config: TransportConfig,
  name: string,
  args: Record<string, string>,
): Promise<{ description?: string; text: string }> {
  return withReadRetry(`prompt/get:${name}`, () =>
    withClient(config, `prompt/get:${name}`, async (client) => {
      const response = McpPromptResultSchema.parse(
        await client.getPrompt({ name, arguments: args }),
      );
      const text = response.messages[0]?.content.text ?? '';
      return response.description ? { description: response.description, text } : { text };
    }),
  );
}

export async function callTool(
  config: TransportConfig,
  name: string,
  args: Record<string, unknown>,
  isMutation: boolean,
): Promise<unknown> {
  const execute = () =>
    withClient(config, `tool/call:${name}`, async (client) => {
      const response = McpToolCallResultSchema.parse(
        await client.callTool({ name, arguments: args }),
      );

      if (response.isError) {
        const text = response.content[0]?.text ?? `Tool "${name}" failed.`;
        throw mapToolError(name, text);
      }

      return normalizeToolData(response);
    });

  if (isMutation) {
    return execute();
  }

  return withReadRetry(`tool/call:${name}`, execute);
}

export async function connectAndSnapshot(config: TransportConfig): Promise<{
  accountSummary: AccountSummary;
  snapshot: CapabilitySnapshot;
}> {
  const [health, tools, prompts, me] = await Promise.all([
    getHealth(config.endpoint),
    listTools(config),
    listPrompts(config),
    callTool(config, 'get_me', {}, false).then((payload) => AccountSummarySchema.parse(payload)),
  ]);

  const snapshot: CapabilitySnapshot = {
    serverVersion: health.version,
    toolNames: tools.map((tool) => tool.name),
    promptIds: prompts.map((prompt) => prompt.name),
    detectedScopes: [
      'authenticated',
      me.isPlatformAdmin ? 'platform_admin' : 'member',
      me.tier ? `tier:${me.tier}` : 'tier:unknown',
    ],
    fetchedAt: new Date().toISOString(),
  };

  return {
    accountSummary: me,
    snapshot,
  };
}
