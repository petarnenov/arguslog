#!/usr/bin/env bash
# Sync customer-facing packages from the private monorepo into the public mirror at
# https://github.com/petarnenov/arguslog-sdks. Designed to be called both locally
# (developer triggers a manual sync) and from CI (`sync-public-mirror.yml` workflow on
# every push to `main`).
#
# What it does:
#  1. Cleans the target mirror directory (everything except .git).
#  2. Rsyncs the public subset of packages from the source monorepo.
#  3. Drops the SDK-focused README, LICENSE, workspace manifests, and license-friendly
#     CI workflows (release-*; deploy-* stay out).
#  4. Stages the result for the caller to commit/push.
#
# Usage:
#   scripts/sync-public-mirror.sh <source_repo_dir> <target_mirror_dir>
#
# Both directories must already exist. The target should be a git checkout of the public
# mirror so the caller can commit/push afterwards.

set -euo pipefail

SRC="${1:?source repo dir required}"
DST="${2:?target mirror dir required}"

if [[ ! -d "$SRC" ]]; then
  echo "✗ source dir not found: $SRC" >&2
  exit 1
fi
if [[ ! -d "$DST/.git" ]]; then
  echo "✗ target is not a git repo: $DST" >&2
  exit 1
fi

cd "$DST"

# Wipe everything tracked by git so removed files in the source actually disappear from
# the mirror. We leave the .git dir + any untracked junk alone — `git add -A` will pick
# up adds, deletes, and modifies cleanly afterwards.
echo "▶ cleaning $DST"
find . -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +

# Customer-facing packages — keep this list in sync with the strategy doc in README.
PUBLIC_PACKAGES=(
  packages/sdk-core
  packages/sdk-browser
  packages/sdk-react
  packages/sdk-vue
  packages/sdk-angular
  packages/sdk-nextjs
  packages/sdk-react-native
  packages/sdk-node
  packages/sdk-web3
  packages/mcp-server
  packages/eslint-config
  packages/tsconfig
  cli
  java-sdk
  python-sdk
)

# Files at the workspace root that the SDKs need to install / build correctly. Some are
# pulled from scripts/public-mirror/ to override the monorepo versions (the monorepo
# `package.json` references `apps/`, `e2e/`, etc. which don't live in the mirror).
ROOT_FILES=(
  pnpm-lock.yaml
  .npmrc
  tsconfig.json
  build.gradle.kts
  gradle.properties
  gradlew
  gradlew.bat
)

ROOT_DIRS=(
  gradle
  buildSrc
)

# Workflow files customers / contributors need to see — every release-*.yml. We leave
# deploy-staging.yml, deploy.yml, pr.yml in the private repo because they reference
# Railway tokens, dashboard endpoints, etc.
mkdir -p "$DST/.github/workflows"
for wf in "$SRC"/.github/workflows/release-*.yml; do
  [[ -f "$wf" ]] || continue
  cp "$wf" "$DST/.github/workflows/$(basename "$wf")"
done

# Public-mirror-specific top-level files. README, package.json, settings.gradle.kts and
# pnpm-workspace.yaml are crafted in scripts/public-mirror/ — the monorepo versions
# reference apps/* / e2e/ / services/* which the mirror doesn't carry.
cp "$SRC/scripts/public-mirror/README.md" "$DST/README.md"
cp "$SRC/scripts/public-mirror/package.json" "$DST/package.json"
cp "$SRC/scripts/public-mirror/settings.gradle.kts" "$DST/settings.gradle.kts"
cp "$SRC/scripts/public-mirror/gitignore" "$DST/.gitignore"
cp "$SRC/LICENSE" "$DST/LICENSE"
cp "$SRC/pnpm-public-mirror.yaml" "$DST/pnpm-workspace.yaml"

# Bring in the docs file the landing page already links to.
mkdir -p "$DST/docs"
cp "$SRC/docs/sdks.md" "$DST/docs/sdks.md"

# scripts/verify-tag-version.mjs is shared between private + public release workflows.
mkdir -p "$DST/scripts"
cp "$SRC/scripts/verify-tag-version.mjs" "$DST/scripts/verify-tag-version.mjs"
[[ -f "$SRC/scripts/package.json" ]] && cp "$SRC/scripts/package.json" "$DST/scripts/package.json"

# Public packages.
for pkg in "${PUBLIC_PACKAGES[@]}"; do
  if [[ ! -d "$SRC/$pkg" ]]; then
    echo "  ⚠️  skipped $pkg — not found in source" >&2
    continue
  fi
  mkdir -p "$DST/$(dirname "$pkg")"
  rsync -a --delete \
    --exclude='node_modules' \
    --exclude='dist' \
    --exclude='build' \
    --exclude='coverage' \
    --exclude='.turbo' \
    --exclude='.pytest_cache' \
    --exclude='__pycache__' \
    --exclude='*.tsbuildinfo' \
    --exclude='.venv' \
    "$SRC/$pkg/" "$DST/$pkg/"
done

# Root files / dirs the workspace install + Gradle multi-module need.
for f in "${ROOT_FILES[@]}"; do
  if [[ -f "$SRC/$f" ]]; then
    cp "$SRC/$f" "$DST/$f"
  fi
done
for d in "${ROOT_DIRS[@]}"; do
  if [[ -d "$SRC/$d" ]]; then
    rsync -a --delete --exclude='node_modules' "$SRC/$d/" "$DST/$d/"
  fi
done

echo "✓ mirror prepared in $DST"
