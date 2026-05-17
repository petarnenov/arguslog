# Arguslog — dev orchestration
#
# Single entry point: `make dev` brings up infra, then opens an mprocs TUI
# with api, ingest, worker, and web panels. Quit mprocs with `q` to stop the
# JVM services and web; infra keeps running until `make down`.

SHELL          := /bin/bash
.SHELLFLAGS    := -eu -o pipefail -c
MAKEFLAGS      += --no-print-directory

COMPOSE_FILE   := infra/docker/docker-compose.yml
COMPOSE        := docker compose -f $(COMPOSE_FILE)
GRADLE         := ./gradlew --console=plain
PNPM           := pnpm

.DEFAULT_GOAL  := dev

.PHONY: help dev up down stop restart fresh logs ps \
        api ingest worker web build-sdks \
        install e2e-install e2e \
        build lint typecheck test python-test python-lint \
        deploy-prod deploy-status deploy-keycloak purge-keycloak-cache \
        self-host-test self-host-down \
        clean reset doctor seed demo

PROD_SERVICES  := arguslog-api arguslog-ingest arguslog-worker arguslog-web arguslog-landing arguslog-mcp

## ─── Top-level ─────────────────────────────────────────────────────────────

help: ## Show this help
	@awk 'BEGIN{FS=":.*##"; printf "\nArguslog dev targets:\n"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""

dev: doctor install build-sdks up ## Start the full stack: doctor → install → build-sdks → up → mprocs
	@command -v mprocs >/dev/null || { echo "mprocs not installed. Run: brew install mprocs"; exit 1; }
	@echo "▶ Starting api / ingest / worker / web (quit mprocs with 'q')"
	@# Source .env.local if present so each developer can keep personal env (e.g.
	@# ARGUSLOG_PLATFORM_ADMINS) out of the committed mprocs.yaml. Gitignored.
	@if [ -f .env.local ]; then \
		echo "▶ Loading .env.local"; \
		set -a; . ./.env.local; set +a; \
		mprocs --config mprocs.yaml; \
	else \
		mprocs --config mprocs.yaml; \
	fi

## ─── Infra (Docker Compose) ────────────────────────────────────────────────

up: render-realm ## Start Postgres/Redis/Keycloak/MinIO/MailHog and wait until healthy
	@$(COMPOSE) up -d --wait
	@$(COMPOSE) --profile init run --rm minio-bucket-init

render-realm: ## Render Keycloak realm from template using DEV_HOST (default localhost)
	@DEV_HOST="$${DEV_HOST:-localhost}" services/keycloak/render-realm.sh

down: ## Stop and remove infra containers (volumes preserved)
	@$(COMPOSE) down

stop: down ## Stop EVERYTHING: infra + dev servers (5173/8080/8081/8082) + Gradle daemons + mprocs
	@echo "▶ Killing dev servers on ports 5173/8080/8081/8082..."
	@for port in 5173 8080 8081 8082; do \
		pid=$$(lsof -ti tcp:$$port 2>/dev/null || true); \
		if [ -n "$$pid" ]; then echo "  :$$port → pid $$pid"; kill $$pid 2>/dev/null || true; fi; \
	done
	@echo "▶ Stopping Gradle daemons..."
	@$(GRADLE) --stop >/dev/null 2>&1 || true
	@echo "▶ Stopping mprocs..."
	@pkill -x mprocs 2>/dev/null || true
	@echo "✓ All stopped"

restart: down up ## Restart infra

fresh: ## Recreate infra from scratch — drops volumes, re-pulls images, brings up
	@echo "▶ Removing containers + volumes..."
	@$(COMPOSE) down -v
	@echo "▶ Pulling latest images..."
	@$(COMPOSE) pull
	@echo "▶ Bringing infra back up..."
	@$(COMPOSE) up -d --wait
	@$(COMPOSE) --profile init run --rm minio-bucket-init

logs: ## Tail infra logs
	@$(COMPOSE) logs -f --tail=100

ps: ## Show infra container status
	@$(COMPOSE) ps

## ─── Individual services (run in foreground) ───────────────────────────────

api: ## Run arguslog-api in foreground (port 8081)
	@$(GRADLE) :services:api:bootRun

ingest: ## Run arguslog-ingest in foreground (port 8080)
	@$(GRADLE) :services:ingest:bootRun

worker: ## Run arguslog-worker in foreground (port 8082)
	@$(GRADLE) :services:worker:bootRun

web: build-sdks ## Run web app in foreground (Vite, port 5173)
	@$(PNPM) --filter @arguslog/web dev

build-sdks: ## Build workspace SDKs so Vite can resolve them (tsc-incremental, fast on reruns)
	@# Drop stale tsbuildinfo when dist/ went missing externally — tsc's
	@# incremental cache keys off source mtimes only, so it'd otherwise say
	@# "Done" without emitting and leave Vite without sdk-browser types.
	@for p in packages/sdk-core packages/sdk-browser packages/sdk-node packages/sdk-react packages/sdk-react-native; do \
		if [ ! -f "$$p/dist/index.d.ts" ]; then rm -f "$$p/tsconfig.build.tsbuildinfo"; fi; \
	done
	@# `...sdk-react` means "sdk-react AND its workspace deps" — pnpm builds
	@# them in topological order so sdk-browser finishes before sdk-react needs it.
	@$(PNPM) --filter "@arguslog/sdk-react..." build

## ─── Setup / dependencies ──────────────────────────────────────────────────

install: ## Install pnpm workspaces
	@$(PNPM) install

e2e-install: ## Install Playwright browsers
	@$(PNPM) e2e:install

## ─── Quality gates ─────────────────────────────────────────────────────────

build: ## Full build (Gradle + Turbo)
	@$(GRADLE) build
	@$(PNPM) build

lint: ## Lint all workspaces (TS only; Gradle has its own)
	@$(PNPM) lint

typecheck: ## Type-check all TS workspaces
	@$(PNPM) typecheck

test: ## Run all tests (Gradle + Vitest + pytest)
	@$(GRADLE) test
	@$(PNPM) test
	@$(MAKE) python-test

python-test: ## Run python-sdk tests (uv + pytest)
	@cd python-sdk && uv run pytest

python-lint: ## Run ruff over python-sdk
	@cd python-sdk && uv run ruff check . && uv run ruff format --check .

e2e: ## Run Playwright e2e suite
	@$(PNPM) e2e

## ─── Production deploy ─────────────────────────────────────────────────────

deploy-keycloak: ## Deploy arguslog-keycloak to prod + purge Cloudflare cache for auth.arguslog.org
	@command -v railway >/dev/null || { echo "✗ railway CLI not installed (brew install railway)"; exit 1; }
	@command -v git >/dev/null || { echo "✗ git not on PATH"; exit 1; }
	@SHA=$$(git rev-parse --short HEAD); \
	echo "▶ Stamping GIT_SHA=$$SHA on arguslog-keycloak..."; \
	railway variables --set "GIT_SHA=$$SHA" --service arguslog-keycloak --environment production --skip-deploys >/dev/null; \
	echo "▶ Building + deploying arguslog-keycloak (blocking until build completes)..."; \
	railway up --service arguslog-keycloak --environment production --ci --message "$$SHA"
	@echo "▶ KC deploy finished. Purging CDN cache so theme + IdP changes show up instantly..."
	@$(MAKE) purge-keycloak-cache

purge-keycloak-cache: ## Purge Cloudflare cache for auth.arguslog.org (theme/CSS updates)
	@# KC's resource URL hash is build-time-stable for a given KC version, so CF serves the
	@# same URL for 30 days across multiple deploys (default Cache-Control: max-age=2592000).
	@# `KC_SPI_THEME_STATIC_MAX_AGE=3600` in the Dockerfile drops that to 1h as a safety net,
	@# but explicit purge here is the instant-feedback path so a deployer sees their changes
	@# immediately. Purge-by-hostname is free-tier-supported (unlike purge-by-URL-prefix).
	@command -v curl >/dev/null || { echo "✗ curl required"; exit 1; }
	@command -v jq >/dev/null   || { echo "✗ jq required"; exit 1; }
	@# Load CLOUDFLARE_API_TOKEN + CLOUDFLARE_ZONE_ID from .env if not already in env.
	@if [ -f .env ]; then set -a; . ./.env; set +a; fi; \
	if [ -z "$$CLOUDFLARE_API_TOKEN" ]; then \
	  echo "✗ CLOUDFLARE_API_TOKEN unset (add to .env — see .env.example for setup)"; exit 1; \
	fi; \
	if [ -z "$$CLOUDFLARE_ZONE_ID" ]; then \
	  echo "✗ CLOUDFLARE_ZONE_ID unset (add to .env — see .env.example for setup)"; exit 1; \
	fi; \
	echo "▶ Purging auth.arguslog.org from Cloudflare cache..."; \
	response=$$(curl -sS -X POST \
	  "https://api.cloudflare.com/client/v4/zones/$$CLOUDFLARE_ZONE_ID/purge_cache" \
	  -H "Authorization: Bearer $$CLOUDFLARE_API_TOKEN" \
	  -H "Content-Type: application/json" \
	  --data '{"hosts": ["auth.arguslog.org"]}'); \
	success=$$(printf '%s' "$$response" | jq -r '.success // false'); \
	if [ "$$success" = "true" ]; then \
	  echo "✓ Cache purged for auth.arguslog.org"; \
	else \
	  echo "✗ Purge failed:"; printf '%s\n' "$$response" | jq .; exit 1; \
	fi

deploy-prod: ## Force fresh rebuild of all 6 prod app services in parallel, then check status
	@command -v railway >/dev/null || { echo "✗ railway CLI not installed (brew install railway)"; exit 1; }
	@command -v git >/dev/null || { echo "✗ git not on PATH"; exit 1; }
	@echo "▶ Switching to production environment..."
	@railway environment production >/dev/null
	@SHA=$$(git rev-parse --short HEAD); \
	echo "▶ Stamping GIT_SHA=$$SHA on every service (skip-deploys so the env var write doesn't bounce the container)..."; \
	for svc in $(PROD_SERVICES); do \
		railway variables --set "GIT_SHA=$$SHA" --service "$$svc" --environment production --skip-deploys >/dev/null; \
	done; \
	echo "▶ Triggering parallel railway up for: $(PROD_SERVICES)"; \
	echo "   (railway up uploads local source and rebuilds the Docker image from scratch —"; \
	echo "    NOT railway redeploy which would just bounce the cached image.)"; \
	pids=""; \
	for svc in $(PROD_SERVICES); do \
		echo "  → $$svc"; \
		railway up --service "$$svc" --environment production --ci --message "$$SHA" > /tmp/argus-deploy-$$svc.log 2>&1 & \
		pids="$$pids $$!"; \
	done; \
	echo "▶ Waiting for all builds to complete..."; \
	failed=0; \
	for pid in $$pids; do \
		if ! wait $$pid; then failed=$$((failed + 1)); fi; \
	done; \
	if [ $$failed -gt 0 ]; then \
		echo "✗ $$failed deploy(s) failed — see /tmp/argus-deploy-*.log"; \
		exit 1; \
	fi
	@echo "▶ Verifying deployment state..."
	@$(MAKE) deploy-status

deploy-status: ## Show deployment status; waits past transient BUILDING/DEPLOYING up to 60s.
	@command -v railway >/dev/null || { echo "✗ railway CLI not installed"; exit 1; }
	@command -v python3 >/dev/null || { echo "✗ python3 required for output formatting"; exit 1; }
	@# Railway's GraphQL API lags ~5–10s behind `railway up --ci` exit, so the very first read
	@# right after deploy-prod often catches a service still in BUILDING / DEPLOYING. Poll
	@# while the python script signals "transient" (exit code 2); break on success (0) or
	@# a real failure (1). 12 × 5s = 60s ceiling stops the loop from masking a stuck build.
	@for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do \
		out=$$(railway status --json | python3 -c "$$DEPLOY_STATUS_PY" 2>&1); \
		ec=$$?; \
		if [ $$ec -ne 2 ]; then printf "%s\n" "$$out"; exit $$ec; fi; \
		if [ $$attempt -eq 12 ]; then \
			printf "%s\n\n⏱  Still transient after 60s — investigate manually.\n" "$$out"; \
			exit 1; \
		fi; \
		sleep 5; \
	done

# Heredoc-y python so the Makefile target body stays one line. Reads stdin (railway status JSON),
# prints one row per prod service with status + truncated timestamp, and signals via exit code:
#   0 — every service is in a terminal-good state (SUCCESS / SLEEPING)
#   1 — at least one service hit a terminal-bad state (FAILED / CRASHED / REMOVED)
#   2 — at least one service is mid-flight (BUILDING / DEPLOYING / INITIALIZING / QUEUED)
# The make wrapper retries on exit 2 so a brief Railway API lag right after `railway up` doesn't
# look like a deploy failure.
define DEPLOY_STATUS_PY
import sys, json
TRANSIENT = {"BUILDING", "DEPLOYING", "INITIALIZING", "QUEUED", "WAITING"}
OK = {"SUCCESS", "SLEEPING", "-"}
d = json.load(sys.stdin)
rows = []
for env in d['environments']['edges']:
    if env['node']['name'] != 'production':
        continue
    for s in env['node']['serviceInstances']['edges']:
        sn = s['node']
        dep = sn.get('latestDeployment', {}) or {}
        rows.append((sn['serviceName'], dep.get('status', '-'), (dep.get('createdAt') or '-')[:19]))
rows.sort()
print(f"  {'SERVICE':25} {'STATUS':12} {'DEPLOYED':19}")
print(f"  {'-' * 25} {'-' * 12} {'-' * 19}")
failed, in_flight = [], []
for name, status, ts in rows:
    if status in OK:
        marker = ' '
    elif status in TRANSIENT:
        marker = '⋯'
        in_flight.append(name)
    else:
        marker = '✗'
        failed.append(name)
    print(f"{marker} {name:25} {status:12} {ts:19}")
if failed:
    print(f"\n⚠  Failed deploys: {', '.join(failed)}")
    sys.exit(1)
if in_flight:
    print(f"\n⏳  In progress: {', '.join(in_flight)}")
    sys.exit(2)
endef
export DEPLOY_STATUS_PY

## ─── Self-host ─────────────────────────────────────────────────────────────

self-host-test: ## Boot the full self-host docker-compose stack + assert health on every service
	@if [ ! -f .env ]; then echo "→ seeding .env from .env.example (first run only)"; cp .env.example .env; fi
	@echo "→ building JVM + web images"
	@docker compose -f infra/docker/docker-compose.full.yml build api ingest worker web
	@echo "→ bringing up infra + creating MinIO buckets"
	@docker compose -f infra/docker/docker-compose.full.yml --profile init up -d --wait \
	  postgres redis keycloak minio mailhog minio-bucket-init
	@echo "→ bringing up api/ingest/worker/web"
	@docker compose -f infra/docker/docker-compose.full.yml up -d --wait api ingest worker web
	@echo "→ probing /actuator/health endpoints"
	@for url in "http://localhost:8081/actuator/health/readiness" \
	            "http://localhost:8080/actuator/health" \
	            "http://localhost:8082/actuator/health" \
	            "http://localhost:5173/healthz" \
	            "http://localhost:8180/health/ready"; do \
	  echo -n "    $$url ... "; \
	  for i in 1 2 3 4 5 6 7 8 9 10; do \
	    if curl -fsS "$$url" >/dev/null 2>&1; then echo "✓"; break; fi; \
	    if [ $$i = 10 ]; then echo "✗"; exit 1; fi; \
	    sleep 2; \
	  done; \
	done
	@echo "→ stack is healthy. dashboard: http://localhost:5173"

self-host-down: ## Tear down the self-host stack and drop volumes
	@docker compose -f infra/docker/docker-compose.full.yml down -v --remove-orphans

## ─── Cleanup ───────────────────────────────────────────────────────────────

clean: ## Remove build artifacts (keep node_modules, keep volumes)
	@$(PNPM) clean || true
	@$(GRADLE) clean || true

reset: ## Nuke everything: containers, volumes, build artifacts, node_modules
	@$(COMPOSE) down -v
	@rm -rf node_modules .turbo
	@$(GRADLE) clean || true
	@find . -type d \( -name "node_modules" -o -name ".turbo" -o -name "build" -o -name "dist" \) -prune -exec rm -rf {} + 2>/dev/null || true

doctor: ## Verify required tools are installed; exit non-zero on misses with OS-specific install commands
	@bash scripts/doctor.sh

seed: ## Seed a demo Keycloak user + org + project + 8-12 synthetic events. Run after `make` is up.
	@bash scripts/seed-demo.sh

demo: ## Full reset → fresh infra → start dev stack → auto-seed demo data
	@$(MAKE) reset
	@$(MAKE) fresh
	@echo ""
	@echo "▶ Demo seed will auto-run as soon as api becomes healthy."
	@echo "  Seed output → /tmp/arguslog-seed.log (tail it from another terminal)."
	@echo ""
	@# Background watcher: polls api readiness up to ~4 minutes, runs seed once it
	@# answers, exits. Inherits the parent shell's process group so a clean mprocs
	@# 'q' lets it finish; Ctrl-C in the foreground kills the whole subtree.
	@( \
	  for i in $$(seq 1 120); do \
	    if curl -fsS http://localhost:8081/actuator/health/readiness >/dev/null 2>&1; then \
	      printf '[demo] api ready after %ds; running seed…\n' $$((i * 2)) > /tmp/arguslog-seed.log; \
	      bash scripts/seed-demo.sh >> /tmp/arguslog-seed.log 2>&1 \
	        || echo "[demo] seed failed — inspect /tmp/arguslog-seed.log" >> /tmp/arguslog-seed.log; \
	      break; \
	    fi; \
	    sleep 2; \
	  done; \
	) &
	@# Pre-seed the platform-admin allowlist with the demo email so the auto-seeded
	@# `demo@arguslog.local` lands on a stack that surfaces the Admin sidebar item
	@# out of the box. Demo-only by design: plain `make` / `make dev` don't go
	@# through this recipe and stay admin-less. Override-friendly (developer's
	@# shell or `.env.local` wins because `dev` sources it after this var is set).
	@ARGUSLOG_PLATFORM_ADMINS="$${ARGUSLOG_PLATFORM_ADMINS:-demo@arguslog.local}" $(MAKE)
