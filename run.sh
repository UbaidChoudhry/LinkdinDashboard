#!/usr/bin/env bash
#
# Starts the jobdash backend (Spring Boot, :8080) and front end (Vite, :5173).
# Both run in the background with logs under logs/. Use ./stop.sh to shut down.
#
set -euo pipefail
cd "$(dirname "$0")"

# Load local secrets (salary-enrichment API keys) from .env if present, so the backend
# picks them up via its ${ADZUNA_APP_ID:} etc. placeholders. .env is gitignored.
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

BACKEND_PORT=8080
FRONTEND_PORT=5173
LOG_DIR="logs"
PID_DIR=".pids"
HEALTH_PATH="/api/runs"
STARTUP_TIMEOUT=90

WANT_BACKEND=1
WANT_FRONTEND=1

usage() {
  cat <<'USAGE'
Usage: ./run.sh [--backend-only | --frontend-only]

Starts the jobdash backend on :8080 and the Vite dev server on :5173.
Logs stream to logs/backend.log and logs/frontend.log.
Stop everything with ./stop.sh
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --backend-only)  WANT_FRONTEND=0 ;;
    --frontend-only) WANT_BACKEND=0 ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$LOG_DIR" "$PID_DIR" data data/resumes
ROOT="$PWD"

# The AI resume/job match step shells out to the Claude CLI. It is optional - everything
# else works without it - so this warns rather than failing the boot. CLAUDE_CLI_PATH
# overrides the binary name (see the ai: block in application.yml).
CLAUDE_BIN="${CLAUDE_CLI_PATH:-claude}"
if command -v "$CLAUDE_BIN" >/dev/null 2>&1; then
  echo "claude CLI    $(command -v "$CLAUDE_BIN") ($("$CLAUDE_BIN" --version 2>/dev/null | head -1))"
else
  echo "warning: '$CLAUDE_BIN' is not on PATH - AI resume matching will be unavailable." >&2
  echo "         Everything else (sweeps, filters, salary) works without it." >&2
  echo "         Install it, or set CLAUDE_CLI_PATH to the binary, then restart." >&2
fi

# Apply with Claude drives a real Chrome tab via the `claude-in-chrome` MCP server, which
# needs the Claude in Chrome extension and a one-time interactive handshake. That handshake
# writes this native-messaging host file - Chrome only reads the NativeMessagingHosts
# directory at startup, so its presence (not the extension itself) is what we can check from
# a shell script. Optional, like the claude CLI above - warn, never fail the boot.
CHROME_NATIVE_HOST="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.anthropic.claude_code_browser_extension.json"
if [ -f "$CHROME_NATIVE_HOST" ]; then
  echo "chrome        Claude in Chrome integration configured"
else
  echo "warning: Claude in Chrome is not set up - 'Apply with Claude' will be unavailable." >&2
  echo "         Install the extension, run 'claude --chrome' once from this directory," >&2
  echo "         then restart Chrome. Everything else works without it." >&2
fi

# Safety net for schema migrations. V5 REBUILDS job_listing (SQLite cannot alter a primary
# key, so the table is recreated and copied), and a rebuild is the one migration shape that
# can lose rows if it goes wrong. Keep one snapshot per day, and only the 3 most recent -
# this is a local dev database, not an archive, and the file is ~90MB once LCA data is in.
backup_database() {
  local db="data/jobdash.db" dir="data/backups" stamp
  [ -f "$db" ] || return 0
  stamp="$(date +%Y%m%d)"
  mkdir -p "$dir"
  if [ ! -f "$dir/jobdash-$stamp.db" ]; then
    # .backup is SQLite's own consistent-snapshot command; copying the file by hand can
    # capture a torn write, because the real state is spread across the -wal file too.
    if command -v sqlite3 >/dev/null 2>&1; then
      sqlite3 "$db" ".backup '$dir/jobdash-$stamp.db'" 2>/dev/null \
        && echo "database    backed up to $dir/jobdash-$stamp.db"
    else
      cp "$db" "$dir/jobdash-$stamp.db" && echo "database    copied to $dir/jobdash-$stamp.db"
    fi
  fi
  # Newest 3 kept; ls -t is safe here because we control the filenames (no spaces).
  ls -t "$dir"/jobdash-*.db 2>/dev/null | tail -n +4 | while read -r old; do rm -f "$old"; done
}
backup_database

# lsof exits non-zero when nothing is listening, which is the expected case here,
# so swallow it rather than letting `set -e` abort the script.
port_pid() { lsof -ti "tcp:$1" 2>/dev/null | head -1 || true; }

require_free_port() {
  local port="$1" name="$2" pid
  pid="$(port_pid "$port")" || true
  if [ -n "$pid" ]; then
    echo "error: $name port $port is already in use (pid $pid)." >&2
    echo "       Run ./stop.sh first, or use --backend-only / --frontend-only." >&2
    exit 1
  fi
}

wait_for_http() {
  local url="$1" name="$2" logfile="$3" waited=0
  printf '  waiting for %s' "$name"
  while [ "$waited" -lt "$STARTUP_TIMEOUT" ]; do
    if curl -sf -o /dev/null "$url" 2>/dev/null; then
      printf ' ok (%ss)\n' "$waited"
      return 0
    fi
    printf '.'
    sleep 2
    waited=$((waited + 2))
  done
  printf ' FAILED after %ss\n' "$STARTUP_TIMEOUT" >&2
  echo "  last 20 lines of $logfile:" >&2
  tail -20 "$logfile" >&2 || true
  return 1
}

if [ "$WANT_BACKEND" -eq 1 ]; then
  require_free_port "$BACKEND_PORT" backend
  echo "starting backend on :$BACKEND_PORT"
  nohup ./mvnw -q spring-boot:run > "$LOG_DIR/backend.log" 2>&1 &
  echo $! > "$PID_DIR/backend.pid"
  if ! wait_for_http "http://localhost:$BACKEND_PORT$HEALTH_PATH" "backend" "$LOG_DIR/backend.log"; then
    echo "backend failed to start; shutting down" >&2
    ./stop.sh >/dev/null 2>&1 || true
    exit 1
  fi
fi

if [ "$WANT_FRONTEND" -eq 1 ]; then
  require_free_port "$FRONTEND_PORT" frontend
  if [ ! -d frontend/node_modules ]; then
    echo "error: frontend/node_modules is missing. Run: (cd frontend && npm install)" >&2
    exit 1
  fi
  echo "starting front end on :$FRONTEND_PORT"
  # `cd X && cmd & echo` would parse as `(cd X && cmd) & echo`, running the echo from the
  # wrong directory, so cd with `;` and write the pid through an absolute path.
  (
    cd frontend
    nohup npm run dev -- --port "$FRONTEND_PORT" --strictPort > "$ROOT/$LOG_DIR/frontend.log" 2>&1 &
    echo $! > "$ROOT/$PID_DIR/frontend.pid"
  )
  if ! wait_for_http "http://localhost:$FRONTEND_PORT" "front end" "$LOG_DIR/frontend.log"; then
    echo "front end failed to start; shutting down" >&2
    ./stop.sh >/dev/null 2>&1 || true
    exit 1
  fi
fi

echo
echo "jobdash is up:"
if [ "$WANT_FRONTEND" -eq 1 ]; then echo "  dashboard  http://localhost:$FRONTEND_PORT"; fi
if [ "$WANT_BACKEND" -eq 1 ];  then echo "  api        http://localhost:$BACKEND_PORT/api"; fi
echo "  logs       $LOG_DIR/backend.log, $LOG_DIR/frontend.log"
echo "  stop       ./stop.sh"
