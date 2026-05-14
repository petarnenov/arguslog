// Runtime config for the Arguslog dashboard. Loaded BEFORE the main bundle so env.ts can
// read window.__ARGUSLOG_CONFIG__ at import time.
//
// In production the container entrypoint (apps/web/runtime-config.sh) overwrites this file
// at boot with values from ARGUSLOG_WEB_* env vars — change a Keycloak URL, just restart
// the container; no rebuild needed.
//
// The empty defaults below win in two cases:
//   1. `vite dev` — no entrypoint runs; env.ts falls through to VITE_* build-time values
//      (which themselves fall back to localhost defaults).
//   2. Self-host where the operator wiped the file accidentally — same fallback chain.
//
// Empty strings are intentionally treated as "unset" by env.ts so a missing env var
// doesn't mask the dev-time defaults.
window.__ARGUSLOG_CONFIG__ = {};
