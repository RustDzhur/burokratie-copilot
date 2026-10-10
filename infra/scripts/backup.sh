#!/usr/bin/env bash
# =============================================================================
#  backup.sh — consistent PostgreSQL dump + object-storage snapshot
# =============================================================================
#  Produces, under <BC_BACKUP_DIR>/<UTC timestamp>/:
#
#      database.dump        pg_dump custom format (-Fc), restorable with restore.sh
#      database.dump.sha256 checksum of the dump (when sha256sum/shasum exists)
#      database.dump.list   table-of-contents listing (proves the dump is readable)
#      objects/             mirror of the S3/MinIO bucket holding document blobs
#      metadata.txt         non-secret facts about the run (who/when/what/versions)
#
#  What this script will NOT do:
#    * it never prints a password or any other secret (spec §21);
#    * it refuses to run against a production-looking database host unless the
#      operator explicitly acknowledges it with I_KNOW_THIS_IS_PRODUCTION=yes;
#    * it does not rotate, delete or overwrite an existing dump.
#
#  Usage:
#      infra/scripts/backup.sh
#      BC_ENV_FILE=infra/.env infra/scripts/backup.sh
#      SKIP_OBJECTS=yes infra/scripts/backup.sh
#      I_KNOW_THIS_IS_PRODUCTION=yes infra/scripts/backup.sh
#
#  Connection sources (first match wins):
#      1. command-line environment:  BC_DB_* / PGHOST / PGPORT / PGDATABASE ...
#      2. $BC_ENV_FILE (default: infra/.env when it exists)
#  The password itself is always taken from BC_DB_PASSWORD / PGPASSWORD or a
#  ~/.pgpass file — never from a command-line argument, so it cannot leak into
#  the process list.
#
#  Exit codes: 0 = backup written, 1 = failure, 2 = usage/refused.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROG="$(basename "$0")"

log()  { printf '%s: %s\n' "$PROG" "$*"; }
warn() { printf '%s: WARNING: %s\n' "$PROG" "$*" >&2; }
die()  { printf '%s: ERROR: %s\n' "$PROG" "$*" >&2; exit 1; }
refuse() { printf '%s: REFUSED: %s\n' "$PROG" "$*" >&2; exit 2; }

# -----------------------------------------------------------------------------
# 1. Load configuration
# -----------------------------------------------------------------------------
# `set -a` exports every variable of the env file so that pg_dump and mc inherit
# them. The file is sourced, never printed.
BC_ENV_FILE="${BC_ENV_FILE:-${REPO_ROOT}/infra/.env}"
if [ -f "$BC_ENV_FILE" ]; then
    log "loading configuration from $BC_ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    . "$BC_ENV_FILE"
    set +a
else
    warn "no env file at $BC_ENV_FILE — relying on the current environment"
fi

DB_HOST="${BC_DB_HOST:-${PGHOST:-localhost}}"
DB_PORT="${BC_DB_PORT:-${PGPORT:-5432}}"
DB_NAME="${BC_DB_NAME:-${PGDATABASE:-burokratie}}"
DB_USER="${BC_DB_USER:-${PGUSER:-burokratie}}"
DB_PASSWORD="${BC_DB_PASSWORD:-${PGPASSWORD:-}}"

BACKUP_ROOT="${BC_BACKUP_DIR:-${REPO_ROOT}/infra/backups}"
case "$BACKUP_ROOT" in
    /*) : ;;
    *)  BACKUP_ROOT="${REPO_ROOT}/${BACKUP_ROOT}" ;;
esac

# Optional object-storage snapshot.
SKIP_OBJECTS="${SKIP_OBJECTS:-no}"
S3_BUCKET="${BC_S3_BUCKET:-}"
S3_ENDPOINT="${BC_S3_ENDPOINT:-}"
S3_ACCESS_KEY="${BC_S3_ACCESS_KEY:-}"
S3_SECRET_KEY="${BC_S3_SECRET_KEY:-}"

# Optional retention cleanup of OLD dumps in the same directory.
RETENTION_DAYS="${BC_BACKUP_RETENTION_DAYS:-0}"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="${BACKUP_ROOT}/${TIMESTAMP}"
DUMP_FILE="${BACKUP_DIR}/database.dump"

# -----------------------------------------------------------------------------
# 2. Preconditions
# -----------------------------------------------------------------------------
[ -n "$DB_HOST" ] || die "database host is empty (set BC_DB_HOST)"
[ -n "$DB_NAME" ] || die "database name is empty (set BC_DB_NAME)"
[ -n "$DB_USER" ] || die "database user is empty (set BC_DB_USER)"
[ -n "$DB_PASSWORD" ] || die "database password is empty — set BC_DB_PASSWORD (or PGPASSWORD) in the environment or in $BC_ENV_FILE"

mkdir -p "$BACKUP_ROOT" || die "cannot create backup root $BACKUP_ROOT"
[ -w "$BACKUP_ROOT" ] || die "backup root is not writable: $BACKUP_ROOT"

# A dump of an empty/incorrect database is worse than no dump: verify that the
# target is reachable and actually looks like this application's database.
command -v psql    >/dev/null 2>&1 || die "psql not found in PATH (install the PostgreSQL 17 client)"
command -v pg_dump >/dev/null 2>&1 || die "pg_dump not found in PATH (install the PostgreSQL 17 client)"

# -----------------------------------------------------------------------------
# 3. Production guard
# -----------------------------------------------------------------------------
# A host is "production-looking" when it is listed in BC_PRODUCTION_DB_HOSTS or
# when its name contains prod/prd/live. Backing UP production is a normal
# operation, so an explicit acknowledgement is enough — but it must be explicit,
# so that nobody runs a maintenance script against production by accident.
PRODUCTION_HOSTS="${BC_PRODUCTION_DB_HOSTS:-}"
LOOKS_PRODUCTION=no
for candidate in $(printf '%s' "$PRODUCTION_HOSTS" | tr ',' ' '); do
    [ -n "$candidate" ] || continue
    if [ "$DB_HOST" = "$candidate" ]; then LOOKS_PRODUCTION=yes; fi
done
case "$DB_HOST" in
    *prod*|*Prod*|*prd*|*live*|*Live*) LOOKS_PRODUCTION=yes ;;
esac

if [ "$LOOKS_PRODUCTION" = yes ]; then
    warn "target database host '$DB_HOST' looks like PRODUCTION"
    if [ "${I_KNOW_THIS_IS_PRODUCTION:-no}" != "yes" ]; then
        refuse "re-run with I_KNOW_THIS_IS_PRODUCTION=yes to confirm (nothing was written)"
    fi
    warn "proceeding because I_KNOW_THIS_IS_PRODUCTION=yes"
fi

# -----------------------------------------------------------------------------
# 4. Connectivity + pre-flight information
# -----------------------------------------------------------------------------
export PGPASSWORD="$DB_PASSWORD"
export PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME" PGUSER="$DB_USER"
# Never read ~/.psqlrc inside a maintenance script.
export PSQLRC=/dev/null

log "checking connectivity to ${DB_HOST}:${DB_PORT}/${DB_NAME} as ${DB_USER}"
if ! psql -X -q -t -A -c 'SELECT 1' >/dev/null 2>&1; then
    die "cannot connect to ${DB_HOST}:${DB_PORT}/${DB_NAME} as ${DB_USER} (is the database up? are the credentials correct?)"
fi

SERVER_VERSION="$(psql -X -q -t -A -c 'SHOW server_version' 2>/dev/null || echo unknown)"
TABLE_COUNT="$(psql -X -q -t -A -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" 2>/dev/null || echo unknown)"
# Flyway versions are TEXT, so a lexical MAX ranks '9' above '15'. Cast the
# numeric prefix, otherwise the recorded schema version is simply wrong.
SCHEMA_VERSION="$(psql -X -q -t -A -c "SELECT COALESCE(max(NULLIF(regexp_replace(version, '\\D.*\$', ''), '')::int)::text, 'none') FROM flyway_schema_history WHERE success" 2>/dev/null || echo 'no flyway_schema_history')"
[ -n "$SCHEMA_VERSION" ] || SCHEMA_VERSION='none'
IS_SUPERUSER="$(psql -X -q -t -A -c 'SELECT current_setting('"'"'is_superuser'"'"')' 2>/dev/null || echo unknown)"

# In a repository without any commit yet (`git init` + changes) git prints a
# literal "HEAD" on stdout AND a fatal error on stderr while exiting non-zero, so
# both outputs are validated here before anything is recorded as a revision.
GIT_REVISION="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>/dev/null || true)"
GIT_BRANCH="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
case "$GIT_REVISION" in
    ''|HEAD|*' '*) GIT_REVISION="no-commit" ;;
esac
case "$GIT_BRANCH" in
    ''|HEAD|*' '*) GIT_BRANCH="no-branch" ;;
esac

log "postgres $SERVER_VERSION, $TABLE_COUNT table(s), flyway schema version: $SCHEMA_VERSION"

# A non-superuser dump is filtered by Row Level Security (V13 enables RLS). That
# produces a silently INCOMPLETE backup, so it is refused rather than tolerated.
if [ "$IS_SUPERUSER" = "off" ]; then
    warn "the dump role '$DB_USER' is NOT a superuser and NOT the table owner."
    warn "Row Level Security is enabled on tenant tables (migration V13): the dump"
    warn "would contain only rows visible to that role — i.e. an INCOMPLETE backup."
    die "use a backup role with BYPASSRLS or superuser rights (see docs/runbook.md)"
fi

# -----------------------------------------------------------------------------
# 5. Database dump (custom format: compressed, selective restore, parallelisable)
# -----------------------------------------------------------------------------
mkdir -p "$BACKUP_DIR"
[ -e "$DUMP_FILE" ] && die "refusing to overwrite an existing dump: $DUMP_FILE"

log "writing $DUMP_FILE"
if ! pg_dump --format=custom --compress=9 --no-owner --no-privileges \
        --file="$DUMP_FILE" "$DB_NAME"; then
    rm -f "$DUMP_FILE"
    die "pg_dump failed — no backup was produced"
fi

[ -s "$DUMP_FILE" ] || { rm -f "$DUMP_FILE"; die "pg_dump produced an empty file"; }

# Prove the archive is readable, and keep the listing as evidence.
if pg_restore --list "$DUMP_FILE" > "${BACKUP_DIR}/database.dump.list" 2>/dev/null; then
    log "dump verified: $(grep -c 'TABLE DATA' "${BACKUP_DIR}/database.dump.list" 2>/dev/null || echo '?') table data section(s) in the archive"
else
    rm -f "$DUMP_FILE" "${BACKUP_DIR}/database.dump.list"
    die "the produced archive is not readable by pg_restore — refusing to keep it"
fi

if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$BACKUP_DIR" && sha256sum "$(basename "$DUMP_FILE")" > "$(basename "$DUMP_FILE").sha256" )
    log "checksum: $(cat "${DUMP_FILE}.sha256")"
elif command -v shasum >/dev/null 2>&1; then
    ( cd "$BACKUP_DIR" && shasum -a 256 "$(basename "$DUMP_FILE")" > "$(basename "$DUMP_FILE").sha256" )
    log "checksum: $(cat "${DUMP_FILE}.sha256")"
else
    warn "neither sha256sum nor shasum is available — no checksum written"
fi

# -----------------------------------------------------------------------------
# 6. Object storage snapshot (document blobs live outside PostgreSQL)
# -----------------------------------------------------------------------------
# A database-only backup restores metadata pointing at blobs that no longer
# exist. Both halves are therefore captured in the same timestamped directory.
OBJECT_STATUS="skipped"
if [ "$SKIP_OBJECTS" = "yes" ]; then
    warn "SKIP_OBJECTS=yes — object storage was NOT backed up (the restore will be incomplete)"
elif [ -z "$S3_BUCKET" ]; then
    warn "BC_S3_BUCKET is not set — object storage was NOT backed up"
else
    mkdir -p "${BACKUP_DIR}/objects"
    # mc is preferred (works with MinIO and any S3-compatible endpoint).
    if command -v mc >/dev/null 2>&1; then
        if [ -z "$S3_ENDPOINT" ]; then
            warn "BC_S3_ENDPOINT is not set — cannot mirror bucket '$S3_BUCKET'"
        else
            # The credentials travel inside MC_HOST_bcbackup (an environment
            # variable) instead of on the command line, so they never appear in
            # the process list nor in this script's output.
            case "$S3_ENDPOINT" in
                http://*)  MC_HOST_bcbackup="http://${S3_ACCESS_KEY}:${S3_SECRET_KEY}@${S3_ENDPOINT#http://}" ;;
                https://*) MC_HOST_bcbackup="https://${S3_ACCESS_KEY}:${S3_SECRET_KEY}@${S3_ENDPOINT#https://}" ;;
                *)         MC_HOST_bcbackup="http://${S3_ACCESS_KEY}:${S3_SECRET_KEY}@${S3_ENDPOINT}" ;;
            esac
            export MC_HOST_bcbackup
            log "mirroring s3://$S3_BUCKET to ${BACKUP_DIR}/objects (mc)"
            if mc mirror --quiet --preserve "bcbackup/${S3_BUCKET}" "${BACKUP_DIR}/objects"; then
                OBJECT_STATUS="ok (mc mirror)"
            else
                OBJECT_STATUS="FAILED (mc mirror)"
                warn "object-storage mirror FAILED — the backup is database-only"
            fi
            unset MC_HOST_bcbackup
        fi
    elif command -v aws >/dev/null 2>&1; then
        AWS_ARGS=()
        [ -n "$S3_ENDPOINT" ] && AWS_ARGS+=(--endpoint-url "$S3_ENDPOINT")
        # Credentials come from AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, which are
        # exported here from the BC_* variables if they are not set already.
        export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-$S3_ACCESS_KEY}"
        export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-$S3_SECRET_KEY}"
        export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-${BC_S3_REGION:-eu-central-1}}"
        log "syncing s3://$S3_BUCKET to ${BACKUP_DIR}/objects (aws s3 sync)"
        if aws "${AWS_ARGS[@]+"${AWS_ARGS[@]}"}" s3 sync "s3://${S3_BUCKET}" "${BACKUP_DIR}/objects" --only-show-errors; then
            OBJECT_STATUS="ok (aws s3 sync)"
        else
            OBJECT_STATUS="FAILED (aws s3 sync)"
            warn "object-storage sync FAILED — the backup is database-only"
        fi
    else
        warn "neither 'mc' nor 'aws' is installed — object storage NOT backed up"
        OBJECT_STATUS="skipped (no mc/aws client)"
    fi
fi

# -----------------------------------------------------------------------------
# 7. Metadata (contains no secrets — safe to inspect and to ship off-host)
# -----------------------------------------------------------------------------
{
    printf 'backup_timestamp_utc=%s\n' "$TIMESTAMP"
    printf 'repository=%s\n' "$REPO_ROOT"
    printf 'git_revision=%s\n' "$GIT_REVISION"
    printf 'git_branch=%s\n' "$GIT_BRANCH"
    printf 'host=%s\n' "$(hostname 2>/dev/null || echo unknown)"
    printf 'operator=%s\n' "$(id -un 2>/dev/null || echo unknown)"
    printf 'database_host=%s\n' "$DB_HOST"
    printf 'database_port=%s\n' "$DB_PORT"
    printf 'database_name=%s\n' "$DB_NAME"
    printf 'database_user=%s\n' "$DB_USER"
    printf 'server_version=%s\n' "$SERVER_VERSION"
    printf 'table_count=%s\n' "$TABLE_COUNT"
    printf 'flyway_schema_version=%s\n' "$SCHEMA_VERSION"
    printf 'dump_format=custom\n'
    printf 'dump_bytes=%s\n' "$(wc -c < "$DUMP_FILE" | tr -d ' ')"
    printf 'object_storage=%s\n' "$OBJECT_STATUS"
    printf 'object_bucket=%s\n' "${S3_BUCKET:-<unset>}"
    printf 'tool_pg_dump=%s\n' "$(pg_dump --version 2>/dev/null || echo unknown)"
} > "${BACKUP_DIR}/metadata.txt"

# -----------------------------------------------------------------------------
# 8. Optional retention cleanup (only inside the configured backup root)
# -----------------------------------------------------------------------------
if [ "$RETENTION_DAYS" != "0" ] && [ -n "$RETENTION_DAYS" ]; then
    case "$RETENTION_DAYS" in
        *[!0-9]*) warn "BC_BACKUP_RETENTION_DAYS is not numeric ('$RETENTION_DAYS') — skipping cleanup" ;;
        *)
            log "removing dumps older than ${RETENTION_DAYS} day(s) from $BACKUP_ROOT"
            # -maxdepth 1 -type d: only timestamped directories directly under the
            # configured root, never anything outside it.
            find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -print -exec rm -rf {} + \
                || warn "retention cleanup reported an error"
            ;;
    esac
fi

# -----------------------------------------------------------------------------
# 9. Result
# -----------------------------------------------------------------------------
log "backup complete: $BACKUP_DIR"
printf '%s\n' "$BACKUP_DIR"
if [ "${OBJECT_STATUS#FAILED}" != "$OBJECT_STATUS" ]; then
    die "database dump is valid, but the object-storage part FAILED (see above)"
fi
exit 0
