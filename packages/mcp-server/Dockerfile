# syntax=docker/dockerfile:1.7
#
# Build + runtime image for the @arguslog/mcp-server HTTP service. Hosts the MCP Streamable
# HTTP endpoint at /mcp on Railway under mcp.arguslog.org so users don't need to install the
# npm package locally — they just point their MCP client at the URL with their PAT in the
# Authorization header.
#
# Two-stage: pnpm install of the workspace (so dev deps for codegen + tsc are available),
# then a slim runtime image with only the production deps.

FROM node:22-alpine AS builder
WORKDIR /workspace

RUN corepack enable && corepack prepare pnpm@9.10.0 --activate

# Manifest layer for cacheable installs.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml turbo.json .npmrc* ./
COPY packages/eslint-config/package.json ./packages/eslint-config/
COPY packages/tsconfig/package.json ./packages/tsconfig/
# sdk-core is a runtime dep of mcp-server (buildSyntheticEvent for the send_test_event tool).
# Manifest first so the install layer stays cacheable across source-only changes.
COPY packages/sdk-core/package.json ./packages/sdk-core/
COPY packages/mcp-server/package.json ./packages/mcp-server/

RUN pnpm install --frozen-lockfile --filter @arguslog/mcp-server...

# Sources. mcp-server's codegen reads services/api/openapi.json — bring that file too so the
# generate step has data to enumerate tools from. tsconfig is copied in full because
# tsconfig.build.json extends @arguslog/tsconfig/base.json — the install layer only had the
# manifest, the actual config files need to be on disk for tsc.
COPY packages/tsconfig ./packages/tsconfig
COPY packages/eslint-config ./packages/eslint-config
COPY packages/sdk-core ./packages/sdk-core
COPY packages/mcp-server ./packages/mcp-server
COPY services/api/openapi.json ./services/api/openapi.json

# sdk-core has to compile BEFORE mcp-server so the resolved `@arguslog/sdk-core/dist/...`
# imports exist on disk when tsc walks mcp-server's source.
RUN pnpm --filter @arguslog/sdk-core build && \
    pnpm --filter @arguslog/mcp-server build

# ── Runtime ───────────────────────────────────────────────────────────────
FROM node:22-alpine AS runtime
WORKDIR /app

RUN corepack enable && corepack prepare pnpm@9.10.0 --activate

COPY --from=builder /workspace/package.json /workspace/pnpm-lock.yaml /workspace/pnpm-workspace.yaml ./
COPY --from=builder /workspace/packages/mcp-server/package.json ./packages/mcp-server/
COPY --from=builder /workspace/packages/mcp-server/dist ./packages/mcp-server/dist
# sdk-core is a runtime workspace dep — pnpm symlinks it; the dist needs to be on disk for
# the symlink to point at real code.
COPY --from=builder /workspace/packages/sdk-core/package.json ./packages/sdk-core/
COPY --from=builder /workspace/packages/sdk-core/dist ./packages/sdk-core/dist

# Production deps only — drops express types, vitest, eslint, tsconfig packages.
RUN pnpm install --prod --frozen-lockfile --filter @arguslog/mcp-server... && \
    pnpm store prune

ENV NODE_ENV=production
ENV PORT=8080
EXPOSE 8080

# Run the HTTP entry point. Railway sets $PORT; the script reads it.
CMD ["node", "packages/mcp-server/dist/http.js"]
