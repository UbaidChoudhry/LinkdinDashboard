#!/usr/bin/env bash
#
# Bulk importer for DOL LCA disclosure spreadsheets into the jobdash salary DB.
# Imports every .xlsx in a directory, deletes each one once it has actually been
# imported, then exits. Boots its own Spring context -- this is a batch job, not a
# server start.
#
set -euo pipefail
cd "$(dirname "$0")"

DEFAULT_DIR="data/lca"
DELETE_AFTER=true

usage() {
  cat <<'USAGE'
Usage: ./import-lca.sh [directory-or-file] [--keep]

Imports every .xlsx in the given directory (default: data/lca) into the lca_wage
table, oldest filename first, and DELETES each spreadsheet once it has actually
contributed rows. A single .xlsx path also works.

  --keep    import but do not delete the spreadsheets

A file is only ever deleted when the import succeeded AND kept at least one row.
Files that fail, or that parse to zero rows (a changed column layout, or everything
past the 3-year staleness cutoff), are left in place and reported.

Get the spreadsheets from:
  https://www.dol.gov/agencies/eta/foreign-labor/performance
  (LCA Programs -> quarterly .xlsx). Only FY2023 and newer files are worth
  importing -- older rows are past the staleness cutoff and get dropped.

Each file is ~80 MB / ~600k rows and imports in roughly 10-15 seconds.

The backend must be stopped (./stop.sh) -- the import writes to the same SQLite file.
USAGE
}

TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep)    DELETE_AFTER=false ;;
    -h|--help) usage; exit 0 ;;
    -*)        echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
    *)
      if [ -n "$TARGET" ]; then
        echo "error: expected at most one path, got '$TARGET' and '$1'" >&2
        exit 2
      fi
      TARGET="$1"
      ;;
  esac
  shift
done

TARGET="${TARGET:-$DEFAULT_DIR}"

if [ ! -e "$TARGET" ]; then
  echo "error: not found: $TARGET" >&2
  [ "$TARGET" = "$DEFAULT_DIR" ] && \
    echo "       Create it and drop the DOL .xlsx files in: mkdir -p $DEFAULT_DIR" >&2
  exit 1
fi

if [ -d "$TARGET" ]; then
  # -maxdepth 1 so a nested archive folder isn't swept up and deleted.
  COUNT="$(find "$TARGET" -maxdepth 1 -type f -name '*.xlsx' ! -name '~$*' | wc -l | tr -d ' ')"
  if [ "$COUNT" -eq 0 ]; then
    echo "error: no .xlsx files in $TARGET" >&2
    exit 1
  fi
  echo "found $COUNT spreadsheet(s) in $TARGET"
else
  case "$TARGET" in
    *.xlsx) ;;
    *) echo "error: expected a directory or an .xlsx file, got: $TARGET" >&2; exit 1 ;;
  esac
  COUNT=1
fi

ABS_PATH="$(cd "$(dirname "$TARGET")" && pwd)/$(basename "$TARGET")"

# The import runs with no web server (see --spring.main.web-application-type=none below), so
# this is not a port conflict -- it is about the database. A running backend writes to the same
# SQLite file, and two writers invite "database is locked" mid-import. Make the user stop it.
port_pid() { lsof -ti "tcp:8080" 2>/dev/null | head -1 || true; }
PID="$(port_pid)"
if [ -n "$PID" ]; then
  echo "error: the backend is running (pid $PID on :8080)." >&2
  echo "       Importing writes to the same SQLite file. Run ./stop.sh first." >&2
  exit 1
fi

if [ "$DELETE_AFTER" = true ]; then
  echo "importing from $ABS_PATH (spreadsheets will be deleted once imported)"
else
  echo "importing from $ABS_PATH (--keep: spreadsheets will be left in place)"
fi

# web-application-type=none: this is a batch job, so don't bind a port or start Tomcat at all.
exec ./mvnw -q spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --salary.lca.import-path=$ABS_PATH --salary.lca.delete-after-import=$DELETE_AFTER"
