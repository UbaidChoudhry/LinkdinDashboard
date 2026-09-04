#!/usr/bin/env bash
#
# Imports an ATS slug catalog into the jobdash ats_company table.
# Accepts a local JSON file or a URL. Boots its own Spring context -- this is a
# batch job, not a server start.
#
set -euo pipefail
cd "$(dirname "$0")"

DEFAULT_SOURCE="https://raw.githubusercontent.com/elliottdehn/open-jobs/main/slugs.json"
PRUNE=false

usage() {
  cat <<'USAGE'
Usage: ./import-slugs.sh [file-or-url] [--prune]

Imports an ATS slug catalog into the ats_company table. With no argument, pulls the
open-jobs catalog:

  https://raw.githubusercontent.com/elliottdehn/open-jobs/main/slugs.json

Expected JSON shape -- an "ats" object mapping each ATS name to a list of slugs:

  { "ats": { "greenhouse": ["airbnb", "stripe", ...],
             "lever":      ["palantir", ...],
             "workday":    ["nvidia.wd5.myworkdayjobs.com", ...] } }

Only the ATS platforms jobdash actually implements (greenhouse, lever, workday) are
imported; the rest of the file is ignored, so the same catalog keeps working as more
integrations are added.

  --prune   also DELETE every row already marked dead, instead of just leaving it
            disabled. Off by default: a dead row is already skipped by every run,
            and keeping it explains why a company disappeared.

IMPORTED SLUGS ARE DISABLED BY DEFAULT. The catalog holds >15,000 companies and a run
only ever visits companies you have enabled -- enable the ones you want in the
dashboard's Sources tab. Importing never disables or overwrites a company you have
already enabled.

Expect a substantial share of any public catalog to be stale: when this list was last
measured, 8 of 34 sampled Greenhouse slugs and 7 of 8 sampled Lever slugs were already
dead. Dead slugs are detected and retired automatically on the runs that hit them.

The backend must be stopped (./stop.sh) -- the import writes to the same SQLite file.
USAGE
}

SOURCE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --prune)   PRUNE=true ;;
    -h|--help) usage; exit 0 ;;
    -*)        echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
    *)
      if [ -n "$SOURCE" ]; then
        echo "error: expected at most one path or URL, got '$SOURCE' and '$1'" >&2
        exit 2
      fi
      SOURCE="$1"
      ;;
  esac
  shift
done

SOURCE="${SOURCE:-$DEFAULT_SOURCE}"

# A local path is resolved to an absolute one; a URL is passed through untouched.
case "$SOURCE" in
  http://*|https://*)
    LOCATION="$SOURCE"
    ;;
  *)
    if [ ! -f "$SOURCE" ]; then
      echo "error: not found: $SOURCE" >&2
      echo "       Pass a local .json file, a URL, or no argument to use the default catalog." >&2
      exit 1
    fi
    LOCATION="$(cd "$(dirname "$SOURCE")" && pwd)/$(basename "$SOURCE")"
    ;;
esac

# Same rule as import-lca.sh: this is about the database, not the port. A running
# backend writes to the same SQLite file, and two writers invite "database is locked".
PID="$(lsof -ti "tcp:8080" 2>/dev/null | head -1 || true)"
if [ -n "$PID" ]; then
  echo "error: the backend is running (pid $PID on :8080)." >&2
  echo "       Importing writes to the same SQLite file. Run ./stop.sh first." >&2
  exit 1
fi

echo "importing slug catalog from $LOCATION"
if [ "$PRUNE" = true ]; then
  echo "  --prune: rows already marked dead will be deleted"
fi

# web-application-type=none: batch job, so don't bind a port or start Tomcat at all.
exec ./mvnw -q spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --ats.slugs.import-path=$LOCATION --ats.slugs.prune-dead=$PRUNE"
