#!/usr/bin/env bash
# =============================================================================
#  restore.sh — restore a pg_dump archive into a target database
# =============================================================================
#  This script DESTROYS the contents of its target database. Two independent
#  confirmations are therefore required, and a production-looking target needs a
#  third, differently-worded one:
#
#      1. a dump file must be named explicitly (there is no "restore the latest")
#      2. CONFIRM=yes must be set
#      3. FORCE=yes must ALSO be set when the target host looks like production
#         (or is listed in BC_PRODUCTION_DB_HOSTS)
#
#  Usage:
#      BC_ENV_FILE=infra/.env CONFIRM=yes infra/scripts/restore.sh \
#          infra/backups/20260101T030000Z/database.dump burokratie_restore_test
#
#      # inspect first — connects, prints facts, writes nothing:
#      infra/scripts/restore.sh --dry-run infra/backups/.../database.dump mydb
#
#      # overwrite a non-empty database (drops the objects present there):
#      CONFIRM=yes ALLOW_NONEMPTY=yes infra/scripts/restore.sh dump mydb
#
#  Safety rules implemented here:
#    * the target must NOT be the database the dump was taken from (explicitly;
#      override only with ALLOW_SAME_DATABASE=yes, e.g. for a dev loop);
#    * a production-looking target is refused without FORCE=yes;
#    * a non-empty target is refused without ALLOW_NONEMPTY=yes;
#    * no password is ever printed or passed as a command-line argument (§21).
#
#  Exit codes: 0 = restored, 1 = failure, 2 = refused / usage error.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROG="$(basename "$0")"

log()    { printf '%s: %s\n' "$PROG" "$*"; }
warn()   { printf '%s: WARNING: %s\n' "$PROG" "$*" >&2; }
die()    { printf '%s: ERROR: %s\n' "$PROG" "$*" >&2; exit 1; }
refuse() { printf '%s: REFUSED: %s\n' "$PROG" "$*" >&2; exit 2; }

usage() {
    cat <<'USAGE'
Usage: restore.sh [--dry-run] <dump-file> <target-database> [target-host] [target-port]

  <dump-file>        a pg_dump archive in custom format (-Fc), e.g. produced by backup.sh
  <target-database>  database to restore INTO — its contents are replaced
  [target-host]      default: BC_DB_HOST / PGHOST / localhost
  [target-port]      default: BC_DB_PORT / PGPORT / 5432

Required environment for a real restore:
  CONFIRM=yes            acknowledge that the target database will be overwritten
  FORCE=yes              additionally required when the target looks like production
  ALLOW_NONEMPTY=yes     required when the target already contains tables
  ALLOW_SAME_DATABASE=yes  permit target == the database the dump was taken from
  BC_DB_USER / BC_DB_PASSWORD   credentials (or PGUSER / PGPASSWORD / ~/.pgpass)

Options:
  --dry-run    print the plan and the connection facts, change nothing
  -h, --help   this help

Exit codes: 0 = restored, 1 = failure, 2 = refused.
USAGE
}

DRY_RUN=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --)        shift; break ;;
        -*)        echo "$PROG: unknown option: $1" >&2; usage >&2; exit 2 ;;
        *)         break ;;
    esac
done

DUMP_FILE="${1:-}"
TARGET_DB="${2:-}"
TARGET_HOST_ARG="${3:-}"
TARGET_PORT_ARG="${4:-}"

[ -n "$DUMP_FILE" ]  || { usage >&2; refuse "a dump file must be named explicitly"; }
[ -n "$TARGET_DB" ]  || { usage >&2; refuse "a target database must be named explicitly"; }

# -----------------------------------------------------------------------------
# 1. Configuration
# -----------------------------------------------------------------------------
BC_ENV_FILE="${BC_ENV_FILE:-${REPO_ROOT}/infra/.env}"
if [ -f "$BC_ENV_FILE" ]; then
    log "loading configuration from $BC_ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    . "$BC_ENV_FILE"
    set +a
fi

SOURCE_HOST_HINT="${BC_DB_HOST:-${PGHOST:-localhost}}"
TARGET_HOST="${TARGET_HOST_ARG:-${RESTORE_DB_HOST:-$SOURCE_HOST_HINT}}"
TARGET_PORT="${TARGET_PORT_ARG:-${RESTORE_DB_PORT:-${BC_DB_PORT:-${PGPORT:-5432}}}}"
TARGET_USER="${RESTORE_DB_USER:-${BC_DB_USER:-${PGUSER:-burokratie}}}"
TARGET_PASSWORD="${RESTORE_DB_PASSWORD:-${BC_DB_PASSWORD:-${PGPASSWORD:-}}}"

[ -n "$TARGET_PASSWORD" ] || die "no password available — set RESTORE_DB_PASSWORD or BC_DB_PASSWORD (or use ~/.pgpass)"

# -----------------------------------------------------------------------------
# 2. Validate the dump BEFORE touching anything
# -----------------------------------------------------------------------------
[ -f "$DUMP_FILE" ] || die "dump file not found: $DUMP_FILE"
[ -s "$DUMP_FILE" ] || die "dump file is empty: $DUMP_FILE"

command -v pg_restore >/dev/null 2>&1 || die "pg_restore not found in PATH (install the PostgreSQL 17 client)"
command -v psql       >/dev/null 2>&1 || die "psql not found in PATH"
command -v createdb   >/dev/null 2>&1 || warn "createdb not found — the target database must already exist"

if ! pg_restore --list "$DUMP_FILE" >/dev/null 2>&1; then
    die "the file is not a readable pg_dump custom-format archive: $DUMP_FILE"
fi

DUMP_TABLES="$(pg_restore --list "$DUMP_FILE" 2>/dev/null | grep -c 'TABLE DATA' || true)"
log "dump looks valid: ${DUMP_TABLES} table data section(s)"

# Which database was this dump taken from? `pg_dump` records it in the archive
# header (TOC entry "dbname"), which lets us refuse the classic
# "restored the dump over its own source" mistake.
DUMP_SOURCE_DB="$(pg_restore --list "$DUMP_FILE" 2>/dev/null | sed -n 's/^;.*dbname: *\(.*\)$/\1/p' | head -1 || true)"

# -----------------------------------------------------------------------------
# 3. Guards
# -----------------------------------------------------------------------------
PRODUCTION_HOSTS="${BC_PRODUCTION_DB_HOSTS:-}"
LOOKS_PRODUCTION=no
for candidate in $(printf '%s' "$PRODUCTION_HOSTS" | tr ',' ' '); do
    [ -n "$candidate" ] || continue
    if [ "$TARGET_HOST" = "$candidate" ]; then LOOKS_PRODUCTION=yes; fi
done
case "$TARGET_HOST" in
    *prod*|*Prod*|*prd*|*live*|*Live*) LOOKS_PRODUCTION=yes ;;
esac

if [ "$LOOKS_PRODUCTION" = yes ]; then
    warn "target host '$TARGET_HOST' looks like PRODUCTION"
    if [ "${FORCE:-no}" != "yes" ]; then
        refuse "restoring over production requires FORCE=yes (plus CONFIRM=yes) — nothing was changed"
    fi
    if [ "${CONFIRM:-no}" != "yes" ]; then
        refuse "CONFIRM=yes is also required — nothing was changed"
    fi
    warn "proceeding because FORCE=yes and CONFIRM=yes are set"
else
    if [ "${CONFIRM:-no}" != "yes" ]; then
        refuse "set CONFIRM=yes to acknowledge that '$TARGET_DB' on '$TARGET_HOST' will be overwritten (nothing was changed)"
    fi
fi

if [ -n "$DUMP_SOURCE_DB" ] && [ "$DUMP_SOURCE_DB" = "$TARGET_DB" ] && [ "$LOOKS_PRODUCTION" != yes ]; then
    if [ "${ALLOW_SAME_DATABASE:-no}" != "yes" ]; then
        refuse "the target database '$TARGET_DB' is the database this dump was taken FROM — restoring it in place is almost always a mistake (override with ALLOW_SAME_DATABASE=yes)"
    fi
    warn "restoring a dump over its own source database because ALLOW_SAME_DATABASE=yes"
fi

# -----------------------------------------------------------------------------
# 4. Connect and inspect the target
# -----------------------------------------------------------------------------
export PGPASSWORD="$TARGET_PASSWORD"
export PGHOST="$TARGET_HOST" PGPORT="$TARGET_PORT" PGUSER="$TARGET_USER"
export PSQLRC=/dev/null

log "connecting to ${TARGET_HOST}:${TARGET_PORT} as ${TARGET_USER}"
psql -X -q -t -A -d postgres -c 'SELECT 1' >/dev/null 2>&1 \
    || die "cannot connect to ${TARGET_HOST}:${TARGET_PORT} as ${TARGET_USER} (check host, port, credentials)"

TARGET_EXISTS="$(psql -X -q -t -A -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$TARGET_DB'" 2>/dev/null || true)"
if [ "$TARGET_EXISTS" = "1" ]; then
    EXISTING_TABLES="$(psql -X -q -t -A -d "$TARGET_DB" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" 2>/dev/null || echo 0)"
else
    EXISTING_TABLES=0
fi

log "target database: $TARGET_DB (exists: $([ "$TARGET_EXISTS" = 1 ] && echo yes || echo no), tables in schema public: $EXISTING_TABLES)"
log "plan: pg_restore $( [ "$EXISTING_TABLES" -gt 0 ] && echo '--clean --if-exists' ) --no-owner --no-privileges -d $TARGET_DB < $DUMP_FILE"

if [ "$DRY_RUN" -eq 1 ]; then
    log "--dry-run: nothing was changed"
    exit 0
fi

if [ "$EXISTING_TABLES" -gt 0 ] && [ "${ALLOW_NONEMPTY:-no}" != "yes" ]; then
    refuse "target '$TARGET_DB' is not empty ($EXISTING_TABLES tables) — set ALLOW_NONEMPTY=yes to drop and recreate its objects"
fi

# -----------------------------------------------------------------------------
# 5. Create the target if needed
# -----------------------------------------------------------------------------
RESTORE_ARGS=(--no-owner --no-privileges --exit-on-error --single-transaction)
if [ "$EXISTING_TABLES" -gt 0 ]; then
    # Drop the objects that exist so that the dump applies cleanly. This only
    # ever touches the target database named on the command line.
    RESTORE_ARGS+=(--clean --if-exists)
fi

if [ "$TARGET_EXISTS" != "1" ]; then
    log "creating database $TARGET_DB"
    createdb -T template0 -E UTF8 --owner "$TARGET_USER" "$TARGET_DB" \
        || die "could not create database $TARGET_DB (does the role have CREATEDB?)"
else
    log "using existing database $TARGET_DB"
fi

# -----------------------------------------------------------------------------
# 6. Restore
# -----------------------------------------------------------------------------
log "restoring (this replaces the current contents of $TARGET_DB)"
START_TS="$(date +%s)"
if ! pg_restore "${RESTORE_ARGS[@]}" -d "$TARGET_DB" "$DUMP_FILE"; then
    die "pg_restore failed — the target database '$TARGET_DB' is now in an UNKNOWN state; inspect the output above and restore again from the same dump after fixing the cause"
fi
ELAPSED=$(($(date +%s) - START_TS))

# -----------------------------------------------------------------------------
# 7. Post-restore sanity checks
# -----------------------------------------------------------------------------
TABLE_COUNT="$(psql -X -q -t -A -d "$TARGET_DB" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" 2>/dev/null || echo '?')"
SCHEMA_VERSION="$(psql -X -q -t -A -d "$TARGET_DB" -c "SELECT COALESCE(max(NULLIF(regexp_replace(version, '\\D.*\$', ''), '')::int)::text, 'none') FROM flyway_schema_history WHERE success" 2>/dev/null || echo 'no flyway_schema_history')"
[ -n "$SCHEMA_VERSION" ] || SCHEMA_VERSION='none'

if [ "$TABLE_COUNT" = "0" ]; then
    die "restore finished but the target has no tables — the dump appears to be empty or structurally unexpected"
fi

log "restore complete in ${ELAPSED}s — $TABLE_COUNT table(s), flyway schema version: $SCHEMA_VERSION"
log "run 'infra/scripts/verify-restore.sh' for the full integrity report"

# The connection settings were exported for psql/pg_restore; unset the password
# so that a shell sourcing this environment cannot inherit it by accident.
unset PGPASSWORD
exit 0
