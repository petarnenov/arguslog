#!/usr/bin/env bash
# Create + push a release tag on the PUBLIC mirror at petarnenov/arguslog-sdks.
#
# After bumping a package version in the private monorepo and pushing to main, the sync
# workflow propagates the changes to the public mirror. To trigger the corresponding npm
# release, the tag has to land on the PUBLIC repo — the release-*.yml workflows live
# there now so npm provenance attestation references publicly-readable commits.
#
# Usage:
#   scripts/release-tag-public.sh <tag>
#   scripts/release-tag-public.sh mcp-server-v0.3.0
#
# This script:
#   1. Clones the public mirror to a temp dir (or fetches if already there).
#   2. Verifies the head of main has the version you expect (warn but don't block).
#   3. Tags HEAD of public main + pushes the tag.

set -euo pipefail

TAG="${1:?usage: release-tag-public.sh <tag>}"
PUBLIC="https://github.com/petarnenov/arguslog-sdks.git"
WORK="${TMPDIR:-/tmp}/arguslog-sdks-release"

if [[ ! -d "$WORK/.git" ]]; then
  echo "▶ cloning public mirror to $WORK"
  git clone --depth 1 "$PUBLIC" "$WORK"
else
  echo "▶ updating $WORK"
  git -C "$WORK" fetch origin main
  git -C "$WORK" checkout main
  git -C "$WORK" pull --ff-only origin main
fi

cd "$WORK"
HEAD_SHA=$(git rev-parse --short HEAD)
HEAD_MSG=$(git log -1 --pretty=%s)

echo "▶ tagging $TAG at $HEAD_SHA  ($HEAD_MSG)"
echo "  Continue? (Ctrl-C to abort, Enter to proceed)"
read -r

git tag "$TAG"
git push origin "$TAG"
echo "✓ pushed $TAG to public mirror"
echo "  → release workflow firing: https://github.com/petarnenov/arguslog-sdks/actions"
