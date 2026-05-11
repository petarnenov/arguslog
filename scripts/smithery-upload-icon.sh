#!/usr/bin/env bash
# Upload the MCP server icon to Smithery's registry. Reads SMITHERY_API_KEY from the
# environment (or .env.local — source it before running).
#
# Usage:
#   set -a; . .env.local; set +a
#   scripts/smithery-upload-icon.sh [path/to/icon.svg]
#
# Default icon: packages/mcp-server/icon.svg

set -euo pipefail

QUALIFIED="petarnenovpetrov/arguslog"
QUALIFIED_ENC="${QUALIFIED//\//%2F}"
ICON="${1:-packages/mcp-server/icon.svg}"
TOKEN="${SMITHERY_API_KEY:?set SMITHERY_API_KEY (see .env.local)}"

[ -f "$ICON" ] || { echo "✗ icon not found: $ICON"; exit 1; }

mime=$(file --mime-type -b "$ICON")
echo "▶ uploading $ICON ($mime) → smithery server $QUALIFIED"

tmp=$(mktemp)
code=$(curl -s -o "$tmp" -w "%{http_code}" -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -F "icon=@${ICON};type=${mime}" \
  "https://registry.smithery.ai/servers/${QUALIFIED_ENC}/icon")
body=$(cat "$tmp")
rm -f "$tmp"

if [ "$code" = "200" ]; then
  echo "✓ uploaded"
  echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
else
  echo "✗ HTTP $code"
  echo "$body"
  exit 1
fi
