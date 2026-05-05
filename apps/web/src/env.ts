import { z } from 'zod';

const envSchema = z.object({
  VITE_API_BASE_URL: z.string().url().default('http://localhost:8081'),
  VITE_INGEST_BASE_URL: z.string().url().default('http://localhost:8080'),
  VITE_KEYCLOAK_URL: z.string().url().default('http://localhost:8180'),
  VITE_KEYCLOAK_REALM: z.string().default('argus'),
  VITE_KEYCLOAK_CLIENT_ID: z.string().default('argus-web'),
  VITE_DOGFOOD_DSN: z.string().optional(),
  VITE_RELEASE: z.string().default('dev'),
});

export const env = envSchema.parse({
  VITE_API_BASE_URL: import.meta.env.VITE_API_BASE_URL,
  VITE_INGEST_BASE_URL: import.meta.env.VITE_INGEST_BASE_URL,
  VITE_KEYCLOAK_URL: import.meta.env.VITE_KEYCLOAK_URL,
  VITE_KEYCLOAK_REALM: import.meta.env.VITE_KEYCLOAK_REALM,
  VITE_KEYCLOAK_CLIENT_ID: import.meta.env.VITE_KEYCLOAK_CLIENT_ID,
  VITE_DOGFOOD_DSN: import.meta.env.VITE_DOGFOOD_DSN,
  VITE_RELEASE: import.meta.env.VITE_RELEASE,
});
