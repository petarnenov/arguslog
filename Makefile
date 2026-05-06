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

.DEFAULT_GOAL  := help

.PHONY: help dev up down stop restart logs ps \
        api ingest worker web \
        install e2e-install e2e \
        build lint typecheck test \
        clean reset doctor

## ─── Top-level ─────────────────────────────────────────────────────────────

help: ## Show this help
	@awk 'BEGIN{FS=":.*##"; printf "\nArgus dev targets:\n"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""

dev: up ## Start the full stack (infra + 3× JVM services + web) via mprocs
	@command -v mprocs >/dev/null || { echo "mprocs not installed. Run: brew install mprocs"; exit 1; }
	@echo "▶ Starting api / ingest / worker / web (quit mprocs with 'q')"
	@mprocs --config mprocs.yaml

## ─── Infra (Docker Compose) ────────────────────────────────────────────────

up: ## Start Postgres/Redis/Keycloak/MinIO/MailHog and wait until healthy
	@$(COMPOSE) up -d --wait
	@$(COMPOSE) --profile init run --rm minio-bucket-init

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

web: ## Run web app in foreground (Vite, port 5173)
	@$(PNPM) --filter @arguslog/web dev

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

test: ## Run all tests (Gradle + Vitest)
	@$(GRADLE) test
	@$(PNPM) test

e2e: ## Run Playwright e2e suite
	@$(PNPM) e2e

## ─── Cleanup ───────────────────────────────────────────────────────────────

clean: ## Remove build artifacts (keep node_modules, keep volumes)
	@$(PNPM) clean || true
	@$(GRADLE) clean || true

reset: ## Nuke everything: containers, volumes, build artifacts, node_modules
	@$(COMPOSE) down -v
	@rm -rf node_modules .turbo
	@$(GRADLE) clean || true
	@find . -type d \( -name "node_modules" -o -name ".turbo" -o -name "build" -o -name "dist" \) -prune -exec rm -rf {} + 2>/dev/null || true

doctor: ## Verify required tools are installed
	@echo "Checking dev prerequisites..."
	@command -v docker  >/dev/null && echo "  ✓ docker"  || echo "  ✗ docker (install Docker Desktop)"
	@command -v pnpm    >/dev/null && echo "  ✓ pnpm"    || echo "  ✗ pnpm (corepack enable && corepack prepare pnpm@9.10.0 --activate)"
	@command -v mprocs  >/dev/null && echo "  ✓ mprocs"  || echo "  ✗ mprocs (brew install mprocs)"
	@command -v java    >/dev/null && echo "  ✓ java"    || echo "  ✗ java (install JDK 21)"
	@test -x ./gradlew              && echo "  ✓ gradlew" || echo "  ✗ gradlew missing"
