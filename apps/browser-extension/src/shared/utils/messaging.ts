import browser from 'webextension-polyfill';
import type { ZodType } from 'zod';

import { createAppError } from '../types/errors';
import { BackgroundRequestSchema, type BackgroundRequest } from '../types/messages';
import { BackgroundEnvelopeSchema } from '../validation/models';

export async function sendBackgroundRequest<T>(
  request: BackgroundRequest,
  dataSchema: ZodType<T>,
): Promise<T> {
  const parsedRequest = BackgroundRequestSchema.parse(request);
  const rawResponse = await browser.runtime.sendMessage(parsedRequest);
  const envelope = BackgroundEnvelopeSchema.parse(rawResponse);

  if (!envelope.ok) {
    throw envelope.error;
  }

  const parsedData = dataSchema.safeParse(envelope.data);
  if (!parsedData.success) {
    throw createAppError('SCHEMA_DRIFT', 'Background response shape changed.', {
      details: parsedData.error.flatten(),
    });
  }

  return parsedData.data;
}
