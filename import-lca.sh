#!/usr/bin/env bash
#
# One-shot importer for DOL LCA disclosure spreadsheets into the jobdash salary DB.
# Boots its own Spring context, runs the import, then exits. Not a server start.
#
set -euo pipefail
cd "$(dirname "$0")"

usage() {
  cat <<'USAGE'
Usage: ./import-lca.sh <path-to-LCA_Disclosure_Data_*.xlsx>

Imports one quarterly DOL LCA disclosure file into the lca_wage table.

Get the files from:
  https://www.dol.gov/agencies/eta/foreign-labor/performance
  (LCA Programs -> quarterly .xlsx). Only FY2023 and newer files are worth
  importing -- older rows are past the 3-year staleness cutoff and get dropped.

Suggested layout: keep the spreadsheets under data/lca/ (gitignored).
USAGE
}

if [ $# -ne 1 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
  usage
  [ $# -eq 1 ] && exit 0 || exit 2
fi

FILE="$1"
if [ ! -f "$FILE" ]; then
  echo "error: file not found: $FILE" >&2
  exit 1
fi
case "$FILE" in
  *.xlsx) ;;
  *) echo "error: expected an .xlsx file, got: $FILE" >&2; exit 1 ;;
esac

ABS_PATH="$(cd "$(dirname "$FILE")" && pwd)/$(basename "$FILE")"

# The import boots its own context on :8080; refuse if the backend is already running.
port_pid() { lsof -ti "tcp:8080" 2>/dev/null | head -1 || true; }
PID="$(port_pid)"
if [ -n "$PID" ]; then
  echo "error: port 8080 is in use (pid $PID). Run ./stop.sh first." >&2
  exit 1
fi

echo "importing $ABS_PATH ..."
exec ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--salary.lca.import-file=$ABS_PATH"
