# syntax=docker/dockerfile:1.7

# Build stage — pnpm install of the entire workspace because @arguslog/sdk-browser and
# @arguslog/sdk-react are workspace deps; pnpm needs to resolve the link before vite can compile.
FROM node:22-alpine AS builder
WORKDIR /workspace

# Enable pnpm 9 via corepack (no manual npm install -g; matches the version pinned in
# package.json's "packageManager" field).
RUN corepack enable && corepack prepare pnpm@9.10.0 --activate

# Install workspace deps in a separate layer for caching. Bring in just the manifest files first
# so source-only edits don't bust the install cache.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml turbo.json .npmrc* ./
COPY apps/web/package.json ./apps/web/
COPY packages/eslint-config/package.json ./packages/eslint-config/
COPY packages/sdk-browser/package.json ./packages/sdk-browser/
COPY packages/sdk-react/package.json ./packages/sdk-react/
COPY packages/tsconfig/package.json ./packages/tsconfig/
COPY cli/package.json ./cli/
COPY e2e/package.json ./e2e/
COPY scripts/package.json ./scripts/

RUN pnpm install --frozen-lockfile

# Now bring in sources and build.
COPY apps/web ./apps/web
COPY packages ./packages

# Build-time public env — Railway injects these via VITE_* build args. The values are baked into
# the static bundle; rebuilds are required to change them.
ARG VITE_API_BASE_URL
ARG VITE_KEYCLOAK_URL
ARG VITE_KEYCLOAK_REALM
ARG VITE_KEYCLOAK_CLIENT_ID
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL
ENV VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM
ENV VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID

RUN pnpm --filter @arguslog/sdk-browser build \
 && pnpm --filter @arguslog/sdk-react build \
 && pnpm --filter @arguslog/web build

# Runtime stage — Caddy serves the static bundle with SPA fallback so deep links like
# /orgs/acme/billing don't 404 on hard refresh.
FROM caddy:2.8-alpine AS runtime
COPY --from=builder /workspace/apps/web/dist /srv
COPY apps/web/Caddyfile /etc/caddy/Caddyfile

# Railway sets $PORT; the Caddyfile reads it via {$PORT:8080}.
ENV PORT=8080
EXPOSE 8080
