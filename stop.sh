#!/usr/bin/env bash
#
# Stops the jobdash backend and front end started by ./run.sh
#
# Shuts down by listening port rather than by recorded pid, because both
# `mvnw spring-boot:run` and `npm run dev` launch child processes that outlive
# their parent: killing the pid we recorded can leave the real server running.
# Recorded pids are cleaned up as a secondary pass.
#
set -uo pipefail
cd "$(dirname "$0")"

BACKEND_PORT=8080
FRONTEND_PORT=5173
PID_DIR=".pids"
GRACE_SECONDS=10

stopped_any=0

stop_port() {
  local port="$1" name="$2" pids waited
  pids="$(lsof -ti "tcp:$port" 2>/dev/null | sort -u)"
  if [ -z "$pids" ]; then
    echo "  $name (:$port) not running"
    return 0
  fi

  echo "  stopping $name (:$port, pid $(echo "$pids" | tr '\n' ' '))"
  # shellcheck disable=SC2086
  kill -TERM $pids 2>/dev/null

  waited=0
  while [ "$waited" -lt "$GRACE_SECONDS" ]; do
    if [ -z "$(lsof -ti "tcp:$port" 2>/dev/null)" ]; then
      echo "    stopped cleanly"
      stopped_any=1
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  pids="$(lsof -ti "tcp:$port" 2>/dev/null | sort -u)"
  if [ -n "$pids" ]; then
    echo "    did not exit in ${GRACE_SECONDS}s, forcing"
    # shellcheck disable=SC2086
    kill -KILL $pids 2>/dev/null
    sleep 1
  fi
  stopped_any=1
}

reap_pidfile() {
  local file="$PID_DIR/$1.pid" pid
  [ -f "$file" ] || return 0
  pid="$(cat "$file" 2>/dev/null)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null
    sleep 1
    kill -KILL "$pid" 2>/dev/null
  fi
  rm -f "$file"
}

echo "stopping jobdash"
stop_port "$BACKEND_PORT" "backend"
stop_port "$FRONTEND_PORT" "front end"

reap_pidfile backend
reap_pidfile frontend
rmdir "$PID_DIR" 2>/dev/null

if [ "$stopped_any" -eq 1 ]; then
  echo "done"
else
  echo "nothing was running"
fi
