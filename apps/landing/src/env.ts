import { z } from 'zod';

const envSchema = z.object({
  /** Where to fetch the platforms catalog from. */
  VITE_API_BASE_URL: z.string().url().default('http://localhost:8081'),
  /** Where the "Get started" CTA points (the dashboard). */
  VITE_APP_BASE_URL: z.string().url().default('http://localhost:5173'),
  /** Optional dogfood DSN — when set, the landing emits its own errors back into Arguslog. */
  VITE_DOGFOOD_DSN: z.string().optional(),
  VITE_RELEASE: z.string().default('dev'),
});

export const env = envSchema.parse({
  VITE_API_BASE_URL: import.meta.env.VITE_API_BASE_URL,
  VITE_APP_BASE_URL: import.meta.env.VITE_APP_BASE_URL,
  VITE_DOGFOOD_DSN: import.meta.env.VITE_DOGFOOD_DSN,
  VITE_RELEASE: import.meta.env.VITE_RELEASE,
});
