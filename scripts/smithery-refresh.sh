#!/usr/bin/env bash
# Trigger a Smithery re-scan of the hosted MCP server. Reads SMITHERY_API_KEY from the
# environment (or .env.local — source it before running). Pushes a new "external_shttp"
# release pointing at https://mcp.arguslog.org/mcp, then polls the release status until
# success/failure. On success the registry's scan logs reflect the latest tool catalog;
# the cached `/servers/{name}.tools` view may take longer to update — check the web UI.
#
# Usage:
#   set -a; . .env.local; set +a
#   scripts/smithery-refresh.sh

set -euo pipefail

QUALIFIED="petarnenovpetrov/arguslog"
UPSTREAM="https://mcp.arguslog.org/mcp"
TOKEN="${SMITHERY_API_KEY:?set SMITHERY_API_KEY (see .env.local)}"

PAYLOAD=$(cat <<JSON
{
  "type": "external",
  "upstreamUrl": "$UPSTREAM",
  "configSchema": {
    "type": "object",
    "required": ["bearerToken"],
    "properties": {
      "bearerToken": {
        "type": "string",
        "x-to": { "header": "Authorization" },
        "x-from": { "header": "bearerToken" },
        "x-order": 0,
        "description": "Personal Access Token from your Arguslog dashboard → Personal access tokens (format: arglog_pat_...). PAT scopes gate writes server-side — read tools work with any PAT, writes require the matching scope (orgs:write, releases:write, etc.).",
        "format": "password"
      },
      "apiUrl": {
        "type": "string",
        "x-to": { "header": "X-Arguslog-Api-Url" },
        "x-order": 1,
        "default": "https://api.arguslog.org",
        "description": "Override only for self-hosted Arguslog or a staging environment. Leave blank for the production cloud at https://api.arguslog.org.",
        "format": "uri"
      }
    }
  }
}
JSON
)

echo "▶ submitting external release → $UPSTREAM"
resp=$(curl -s -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -F "payload=$PAYLOAD" \
  "https://registry.smithery.ai/servers/$QUALIFIED/releases")
dep=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['deploymentId'])")
echo "▶ deployment id: $dep"

echo "▶ polling status..."
while true; do
  status=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "https://registry.smithery.ai/servers/$QUALIFIED/releases/$dep" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  printf "  %s\n" "$status"
  case "$status" in
    SUCCESS) break ;;
    FAILURE|FAILURE_SCAN|CANCELLED|INTERNAL_ERROR)
      echo "✗ deployment failed"
      curl -s -H "Authorization: Bearer $TOKEN" \
        "https://registry.smithery.ai/servers/$QUALIFIED/releases/$dep" \
        | python3 -m json.tool
      exit 1
      ;;
  esac
  sleep 6
done
echo "✓ scan complete — check https://smithery.ai/servers/$QUALIFIED"
