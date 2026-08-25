#!/usr/bin/env bash
#
# Starts the jobdash backend (Spring Boot, :8080) and front end (Vite, :5173).
# Both run in the background with logs under logs/. Use ./stop.sh to shut down.
#
set -euo pipefail
cd "$(dirname "$0")"

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

mkdir -p "$LOG_DIR" "$PID_DIR" data
ROOT="$PWD"

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
