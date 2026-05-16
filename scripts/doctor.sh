#!/usr/bin/env bash
#
# scripts/doctor.sh — verify local-dev prerequisites for Arguslog.
#
# Detects the host OS via `uname -s` and prints the canonical install command
# for any missing tool. Java is version-checked (must be 21), not just present
# — JDK 11/17 are common on dev boxes and won't build this project.
#
# Exit code: 0 = all green, 1 = at least one prerequisite missing/wrong.

set -u -o pipefail

REQUIRED_JAVA_MAJOR=21
REQUIRED_NODE_MAJOR=22

OS="$(uname -s)"

red()    { printf '\033[31m%s\033[0m' "$1"; }
green()  { printf '\033[32m%s\033[0m' "$1"; }
yellow() { printf '\033[33m%s\033[0m' "$1"; }
bold()   { printf '\033[1m%s\033[0m' "$1"; }

MISSES=()

# Pick the right install hint for a tool, keyed by OS.
# Args: <tool> <macos-cmd> <debian-cmd> <generic-hint>
hint_for() {
  local tool="$1" mac="$2" deb="$3" generic="$4"
  case "$OS" in
    Darwin) echo "$mac" ;;
    Linux)
      if command -v apt-get >/dev/null 2>&1; then
        echo "$deb"
      else
        echo "$generic"
      fi
      ;;
    *) echo "$generic" ;;
  esac
}

record_miss() {
  local tool="$1" hint="$2"
  MISSES+=("$tool|$hint")
  printf '  %s %s\n' "$(red '✗')" "$(bold "$tool")"
  printf '      install: %s\n' "$hint"
}

record_ok() {
  local tool="$1" detail="${2:-}"
  if [ -n "$detail" ]; then
    printf '  %s %s  %s\n' "$(green '✓')" "$(bold "$tool")" "$(yellow "$detail")"
  else
    printf '  %s %s\n' "$(green '✓')" "$(bold "$tool")"
  fi
}

echo "Checking Arguslog dev prerequisites on $(bold "$OS")..."
echo ""

# ── docker ────────────────────────────────────────────────────────────────
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    record_ok "docker" "$(docker --version | head -n1)"
  else
    record_miss "docker (daemon not running)" \
      "Start Docker (e.g. open -a Docker on macOS, sudo systemctl start docker on Linux)"
  fi
else
  record_miss "docker" "$(hint_for docker \
    'brew install --cask docker' \
    'sudo apt install docker.io docker-compose-plugin' \
    'https://docs.docker.com/engine/install/')"
fi

# ── docker compose (v2 plugin) ────────────────────────────────────────────
if docker compose version >/dev/null 2>&1; then
  record_ok "docker compose" "v2 plugin available"
else
  record_miss "docker compose" "$(hint_for compose \
    'Docker Desktop ships it; if separate: brew install docker-compose' \
    'sudo apt install docker-compose-plugin' \
    'https://docs.docker.com/compose/install/')"
fi

# ── pnpm ──────────────────────────────────────────────────────────────────
if command -v pnpm >/dev/null 2>&1; then
  record_ok "pnpm" "$(pnpm -v)"
else
  record_miss "pnpm" "$(hint_for pnpm \
    'brew install pnpm' \
    'curl -fsSL https://get.pnpm.io/install.sh | sh -' \
    'https://pnpm.io/installation')"
fi

# ── node ──────────────────────────────────────────────────────────────────
if command -v node >/dev/null 2>&1; then
  NODE_FULL="$(node -v)"      # e.g. v22.10.0
  NODE_MAJOR="${NODE_FULL#v}"  # strip leading v
  NODE_MAJOR="${NODE_MAJOR%%.*}"
  if [ "$NODE_MAJOR" -ge "$REQUIRED_NODE_MAJOR" ] 2>/dev/null; then
    record_ok "node" "$NODE_FULL"
  else
    record_miss "node (need >=${REQUIRED_NODE_MAJOR}, found ${NODE_FULL})" "$(hint_for node \
      'brew install node@22' \
      'curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs' \
      'https://nodejs.org/')"
  fi
else
  record_miss "node" "$(hint_for node \
    'brew install node@22' \
    'curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs' \
    'https://nodejs.org/')"
fi

# ── java 21 ───────────────────────────────────────────────────────────────
# Prefer /usr/libexec/java_home on macOS — more reliable than parsing `java -version`.
JAVA_OK=0
JAVA_DETAIL=""
if [ "$OS" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
  if JHOME="$(/usr/libexec/java_home -v "$REQUIRED_JAVA_MAJOR" 2>/dev/null)"; then
    JAVA_OK=1
    JAVA_DETAIL="JDK $REQUIRED_JAVA_MAJOR at $JHOME"
  fi
fi
if [ "$JAVA_OK" -eq 0 ] && command -v java >/dev/null 2>&1; then
  # Parse: openjdk version "21.0.5" 2024-10-15 → 21
  JAVA_VERSION_STRING="$(java -version 2>&1 | head -n1)"
  JAVA_MAJOR="$(printf '%s' "$JAVA_VERSION_STRING" | sed -n 's/.*"\([0-9][0-9]*\)\..*/\1/p')"
  if [ "$JAVA_MAJOR" = "$REQUIRED_JAVA_MAJOR" ]; then
    JAVA_OK=1
    JAVA_DETAIL="$JAVA_VERSION_STRING"
  elif [ -n "$JAVA_MAJOR" ]; then
    record_miss "java (need ${REQUIRED_JAVA_MAJOR}, found ${JAVA_MAJOR})" "$(hint_for java \
      'brew install openjdk@21 && echo "export PATH=/opt/homebrew/opt/openjdk@21/bin:\$PATH" >> ~/.zshrc' \
      'curl -s https://get.sdkman.io | bash && sdk install java 21.0.5-tem' \
      'https://adoptium.net/temurin/releases/?version=21')"
  fi
fi
if [ "$JAVA_OK" -eq 1 ]; then
  record_ok "java" "$JAVA_DETAIL"
elif ! command -v java >/dev/null 2>&1; then
  record_miss "java" "$(hint_for java \
    'brew install openjdk@21 && echo "export PATH=/opt/homebrew/opt/openjdk@21/bin:\$PATH" >> ~/.zshrc' \
    'curl -s https://get.sdkman.io | bash && sdk install java 21.0.5-tem' \
    'https://adoptium.net/temurin/releases/?version=21')"
fi

# ── gradle wrapper ────────────────────────────────────────────────────────
if [ -x ./gradlew ]; then
  record_ok "gradlew" "$(./gradlew --version 2>/dev/null | sed -n 's/^Gradle \(.*\)/v\1/p' | head -n1)"
else
  record_miss "gradlew" "Run from the repo root — ./gradlew is committed but not on this path"
fi

# ── mprocs ────────────────────────────────────────────────────────────────
if command -v mprocs >/dev/null 2>&1; then
  record_ok "mprocs" "$(mprocs --version 2>/dev/null | head -n1)"
else
  record_miss "mprocs" "$(hint_for mprocs \
    'brew install mprocs' \
    'cargo install mprocs  # or download a binary from https://github.com/pvolok/mprocs/releases' \
    'https://github.com/pvolok/mprocs#installation')"
fi

# ── jq (used by `make seed`) ──────────────────────────────────────────────
if command -v jq >/dev/null 2>&1; then
  record_ok "jq" "$(jq --version 2>/dev/null)"
else
  record_miss "jq" "$(hint_for jq \
    'brew install jq' \
    'sudo apt install jq' \
    'https://jqlang.github.io/jq/download/')"
fi

# ── curl (for seed + healthchecks) ────────────────────────────────────────
if command -v curl >/dev/null 2>&1; then
  record_ok "curl"
else
  record_miss "curl" "$(hint_for curl \
    'brew install curl' \
    'sudo apt install curl' \
    'https://curl.se/')"
fi

echo ""
if [ "${#MISSES[@]}" -eq 0 ]; then
  echo "$(green '✅ all prerequisites OK') — run $(bold 'make') to bring the stack up."
  exit 0
fi

echo "$(red '❌')  $(bold "${#MISSES[@]} prerequisite(s) missing.") Fix the install commands above and re-run $(bold 'make doctor')."
exit 1
