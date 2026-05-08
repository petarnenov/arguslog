# syntax=docker/dockerfile:1.7

# Build stage — pnpm install of the entire workspace because @arguslog/sdk-browser and
# @arguslog/sdk-react are workspace deps; pnpm needs to resolve the link before vite can compile.
FROM node:22-alpine AS builder
WORKDIR /workspace

RUN corepack enable && corepack prepare pnpm@9.10.0 --activate

# Manifests first so source-only edits don't bust the install cache.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml turbo.json .npmrc* ./
COPY apps/landing/package.json ./apps/landing/
COPY apps/web/package.json ./apps/web/
COPY packages/eslint-config/package.json ./packages/eslint-config/
COPY packages/sdk-browser/package.json ./packages/sdk-browser/
COPY packages/sdk-core/package.json ./packages/sdk-core/
COPY packages/sdk-node/package.json ./packages/sdk-node/
COPY packages/sdk-react/package.json ./packages/sdk-react/
COPY packages/sdk-react-native/package.json ./packages/sdk-react-native/
COPY packages/tsconfig/package.json ./packages/tsconfig/
COPY cli/package.json ./cli/
COPY e2e/package.json ./e2e/
COPY scripts/package.json ./scripts/

RUN pnpm install --frozen-lockfile

# Now bring in sources and build.
COPY apps/landing ./apps/landing
COPY packages ./packages

# Build-time public env — Railway injects via VITE_* build args. Baked into the static bundle;
# rebuilds are required to change them.
ARG VITE_API_BASE_URL
ARG VITE_APP_BASE_URL
ARG VITE_DOGFOOD_DSN
ARG VITE_RELEASE
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_APP_BASE_URL=$VITE_APP_BASE_URL
ENV VITE_DOGFOOD_DSN=$VITE_DOGFOOD_DSN
ENV VITE_RELEASE=$VITE_RELEASE

RUN pnpm --filter @arguslog/sdk-browser build \
 && pnpm --filter @arguslog/sdk-react build \
 && pnpm --filter @arguslog/landing build

# Runtime stage — Caddy serves the static bundle.
FROM caddy:2.8-alpine AS runtime
COPY --from=builder /workspace/apps/landing/dist /srv
COPY apps/landing/Caddyfile /etc/caddy/Caddyfile

ENV PORT=8080
EXPOSE 8080
