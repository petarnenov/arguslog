import { z } from 'zod';

export const ErrorBucketSchema = z.enum([
  'NO_PAT',
  'INVALID_PAT',
  'INSUFFICIENT_SCOPE',
  'THROTTLED',
  'SERVER_UNAVAILABLE',
  'TOOL_MISSING',
  'SCHEMA_DRIFT',
  'VALIDATION_ERROR',
]);

export const AppErrorSchema = z.object({
  bucket: ErrorBucketSchema,
  message: z.string(),
  status: z.number().int().optional(),
  retryAfterMs: z.number().int().nonnegative().optional(),
  details: z.record(z.unknown()).optional(),
});

export type ErrorBucket = z.infer<typeof ErrorBucketSchema>;
export type AppError = z.infer<typeof AppErrorSchema>;

export function createAppError(
  bucket: ErrorBucket,
  message: string,
  extra: Omit<AppError, 'bucket' | 'message'> = {},
): AppError {
  return {
    bucket,
    message,
    ...extra,
  };
}
