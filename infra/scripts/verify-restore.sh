#!/usr/bin/env bash
# =============================================================================
#  verify-restore.sh — prove that the newest backup can actually be restored
# =============================================================================
#  What it does, in order:
#    1. locates the newest backup directory (or uses the one you name);
#    2. restores its database.dump into a THROWAWAY database
#       (default: bc_restore_verify_<UTC timestamp>);
#    3. runs integrity checks against the restored database:
#         * table count matches the count recorded at backup time;
#         * every key table exists and its row count matches the dump;
#         * Flyway schema history is present, complete and has the expected head
#           version (no failed migrations, no missing ranks);
#         * no orphaned rows across ALL foreign keys;
#         * Row Level Security is enabled on the tenant-scoped tables (V13);
#    4. prints a PASS/FAIL report and exits non-zero when anything failed;
#    5. drops the throwaway database (keep it with KEEP=yes).
#
#  A backup that has never been restored is not a backup — spec §27/§34 require a
#  verified restore. Run this on every retention window; a green run is the only
#  evidence that the backup pipeline still works.
#
#  Usage:
#      BC_ENV_FILE=infra/.env infra/scripts/verify-restore.sh
#      CONFIRM=yes infra/scripts/verify-restore.sh infra/backups/20260101T030000Z
#      KEEP=yes infra/scripts/verify-restore.sh          # keep the scratch DB
#      SKIP_OBJECTS_CHECK=yes infra/scripts/verify-restore.sh
#
#  Required environment: credentials for the target PostgreSQL server
#  (BC_DB_USER / BC_DB_PASSWORD or RESTORE_DB_*), CONFIRM=yes.
#  The scratch database is created on the SAME server as the backup target — this
#  script never touches an existing database other than the scratch one.
#
#  Exit codes: 0 = PASS, 1 = FAIL (a check failed or the restore failed),
#              2 = refused / usage error.
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
Usage: verify-restore.sh [--dump <file>] [--scratch <dbname>] [<backup-directory>]

  <backup-directory>   directory created by backup.sh (default: the newest one
                       under $BC_BACKUP_DIR)
  --dump <file>        verify a dump file directly instead of a backup directory
  --scratch <dbname>   name of the throwaway database (default: auto-generated)

Environment:
  BC_BACKUP_DIR          where to look for backups (default: infra/backups)
  CONFIRM=yes            required: a scratch database is created and dropped
  KEEP=yes               keep the scratch database for manual inspection
  RESTORE_DB_HOST/PORT/USER/PASSWORD   target server (defaults to BC_DB_*)
  BC_EXPECTED_TABLES     expected table count; overrides the value in metadata.txt
  BC_KEY_TABLES          space-separated override of the key-table list
  SKIP_OBJECTS_CHECK=yes skip the object-storage presence check

Exit codes: 0 = PASS, 1 = FAIL, 2 = refused / usage error.
USAGE
}

DUMP_ARG=""
SCRATCH_ARG=""
BACKUP_ARG=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --dump)    [ "$#" -ge 2 ] || { echo "$PROG: --dump needs a value" >&2; exit 2; }; DUMP_ARG="$2"; shift 2 ;;
        --scratch) [ "$#" -ge 2 ] || { echo "$PROG: --scratch needs a value" >&2; exit 2; }; SCRATCH_ARG="$2"; shift 2 ;;
        --)        shift; break ;;
        -*)        echo "$PROG: unknown option: $1" >&2; usage >&2; exit 2 ;;
        *)         BACKUP_ARG="$1"; shift ;;
    esac
done

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

BACKUP_ROOT="${BC_BACKUP_DIR:-${REPO_ROOT}/infra/backups}"
case "$BACKUP_ROOT" in
    /*) : ;;
    *)  BACKUP_ROOT="${REPO_ROOT}/${BACKUP_ROOT}" ;;
esac

TARGET_HOST="${RESTORE_DB_HOST:-${BC_DB_HOST:-${PGHOST:-localhost}}}"
TARGET_PORT="${RESTORE_DB_PORT:-${BC_DB_PORT:-${PGPORT:-5432}}}"
TARGET_USER="${RESTORE_DB_USER:-${BC_DB_USER:-${PGUSER:-burokratie}}}"
TARGET_PASSWORD="${RESTORE_DB_PASSWORD:-${BC_DB_PASSWORD:-${PGPASSWORD:-}}}"

[ -n "$TARGET_PASSWORD" ] || die "no password available — set RESTORE_DB_PASSWORD or BC_DB_PASSWORD"
[ "${CONFIRM:-no}" = "yes" ] || refuse "set CONFIRM=yes — this creates and then drops a throwaway database on ${TARGET_HOST}:${TARGET_PORT}"

# A scratch database is still a database on a real server: refuse production.
PRODUCTION_HOSTS="${BC_PRODUCTION_DB_HOSTS:-}"
LOOKS_PRODUCTION=no
for candidate in $(printf '%s' "$PRODUCTION_HOSTS" | tr ',' ' '); do
    [ -n "$candidate" ] || continue
    if [ "$TARGET_HOST" = "$candidate" ]; then LOOKS_PRODUCTION=yes; fi
done
case "$TARGET_HOST" in
    *prod*|*Prod*|*prd*|*live*|*Live*) LOOKS_PRODUCTION=yes ;;
esac
if [ "$LOOKS_PRODUCTION" = yes ] && [ "${FORCE:-no}" != "yes" ]; then
    refuse "target host '$TARGET_HOST' looks like PRODUCTION — a restore drill must not run there (override with FORCE=yes only if you have a dedicated scratch server and know why)"
fi

command -v psql       >/dev/null 2>&1 || die "psql not found in PATH"
command -v pg_restore >/dev/null 2>&1 || die "pg_restore not found in PATH"
[ -x "${SCRIPT_DIR}/restore.sh" ] || die "restore.sh is missing or not executable next to this script"

# -----------------------------------------------------------------------------
# 2. Locate the backup
# -----------------------------------------------------------------------------
DUMP_FILE=""
BACKUP_DIR=""
if [ -n "$DUMP_ARG" ]; then
    DUMP_FILE="$DUMP_ARG"
    case "$DUMP_FILE" in /*) : ;; *) DUMP_FILE="${REPO_ROOT}/${DUMP_FILE}" ;; esac
    BACKUP_DIR="$(dirname "$DUMP_FILE")"
else
    if [ -n "$BACKUP_ARG" ]; then
        BACKUP_DIR="$BACKUP_ARG"
        case "$BACKUP_DIR" in /*) : ;; *) BACKUP_DIR="${REPO_ROOT}/${BACKUP_DIR}" ;; esac
    else
        [ -d "$BACKUP_ROOT" ] || die "backup root does not exist: $BACKUP_ROOT (run infra/scripts/backup.sh first)"
        # Newest first: directory names are UTC timestamps (YYYYMMDDTHHMMSSZ), so
        # a lexical sort is also a chronological sort.
        BACKUP_DIR="$(find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)"
        [ -n "$BACKUP_DIR" ] || die "no backup directory found under $BACKUP_ROOT"
    fi
    DUMP_FILE="${BACKUP_DIR}/database.dump"
fi

[ -d "$BACKUP_DIR" ] || die "backup directory not found: $BACKUP_DIR"
[ -f "$DUMP_FILE" ]  || die "dump file not found: $DUMP_FILE (expected database.dump)"
[ -s "$DUMP_FILE" ]  || die "dump file is empty: $DUMP_FILE"

log "backup under test: $BACKUP_DIR"
log "dump file:         $DUMP_FILE ($(wc -c < "$DUMP_FILE" | tr -d ' ') bytes)"

# -----------------------------------------------------------------------------
# 3. Expected values (from metadata.txt when available, else from the dump)
# -----------------------------------------------------------------------------
METADATA="${BACKUP_DIR}/metadata.txt"
EXPECTED_TABLES="${BC_EXPECTED_TABLES:-}"
EXPECTED_FLYWAY=""
BACKUP_DB_NAME=""
if [ -f "$METADATA" ]; then
    [ -n "$EXPECTED_TABLES" ] || EXPECTED_TABLES="$(sed -n 's/^table_count=//p' "$METADATA" | head -1)"
    EXPECTED_FLYWAY="$(sed -n 's/^flyway_schema_version=//p' "$METADATA" | head -1)"
    BACKUP_DB_NAME="$(sed -n 's/^database_name=//p' "$METADATA" | head -1)"
else
    warn "no metadata.txt next to the dump — expected values are derived from the archive itself"
fi
# `unknown`/empty means the value could not be recorded; treat it as "not known"
# rather than comparing against a literal.
case "$EXPECTED_TABLES" in ''|unknown|'?') EXPECTED_TABLES="" ;; esac
case "$EXPECTED_FLYWAY" in ''|unknown|'?'|none|no\ flyway_schema_history) EXPECTED_FLYWAY="" ;; esac

# Key tables: every row count is compared after the restore. A table that does
# not exist is a failure unless it is listed in OPTIONAL_TABLES. The list spans
# every domain of the data model — identity/auth, tenancy, documents, cases,
# tasks, contracts, finance, mail, workflow, integrations, devices,
# notifications, system settings, audit — plus the outbox and idempotency tables,
# so that a partial restore cannot slip through. Table names are verified against
# services/api/src/main/resources/db/migration (V1..V15).
KEY_TABLES="${BC_KEY_TABLES:-tenants users user_profiles organizations workspaces memberships roles permissions role_permissions documents document_versions document_blobs document_pages document_extractions document_classifications document_templates template_versions cases case_documents case_participants contacts tasks task_assignees task_deadlines contracts contract_parties invoices invoice_lines payments financial_accounts bank_transactions mail_messages mail_attachments workflow_definitions workflow_runs workflow_events approvals audit_events access_change_log integrations integration_credentials integration_sync_runs devices device_jobs scan_sessions notifications notification_preferences system_settings feature_flags saved_views api_tokens webhook_endpoints webhook_events outbox_events outbox_consumptions idempotency_records ai_jobs ai_model_runs ai_usage_records customers suppliers purchase_orders credit_notes e_invoice_records}"
# Tables that legitimately may be missing from a backup taken before the feature
# existed; everything else must be present.
OPTIONAL_TABLES="${BC_OPTIONAL_TABLES:-e_invoice_records scan_sessions ai_jobs ai_model_runs ai_usage_records}"

export PGPASSWORD="$TARGET_PASSWORD"
export PGHOST="$TARGET_HOST" PGPORT="$TARGET_PORT" PGUSER="$TARGET_USER"
export PSQLRC=/dev/null

psql -X -q -t -A -d postgres -c 'SELECT 1' >/dev/null 2>&1 \
    || die "cannot connect to ${TARGET_HOST}:${TARGET_PORT} as ${TARGET_USER}"

# -----------------------------------------------------------------------------
# 4. Expected row counts, read straight from the archive (no restore needed)
# -----------------------------------------------------------------------------
# `pg_restore --data-only --file=-` re-emits the COPY blocks on stdout (without
# `-f` it refuses to run, and without `-d` it has no target): counting the data
# lines per table yields an expectation that is independent of metadata.txt.
EXPECTED_ROWS_FILE="$(mktemp "${TMPDIR:-/tmp}/bc-expected-rows.XXXXXX")"
trap 'rm -f "$EXPECTED_ROWS_FILE"' EXIT

pg_restore --data-only --no-owner --file=- "$DUMP_FILE" 2>/dev/null \
    | awk '
        /^COPY / {
            # COPY public.<table> (<cols>) FROM stdin;
            line = $0
            sub(/^COPY /, "", line)
            split(line, parts, " ")
            table = parts[1]
            sub(/^.*\./, "", table)          # strip the schema qualifier
            gsub(/"/, "", table)
            current = table
            count[current] = 0
            next
        }
        /^\\\.$/ { current = ""; next }
        current != "" { count[current]++ }
        END { for (t in count) printf "%s %d\n", t, count[t] }
    ' | sort > "$EXPECTED_ROWS_FILE"

EXPECTED_ROWS_TABLES="$(wc -l < "$EXPECTED_ROWS_FILE" | tr -d ' ')"
log "archive contains data for $EXPECTED_ROWS_TABLES table(s)"

# -----------------------------------------------------------------------------
# 5. Restore into a throwaway database
# -----------------------------------------------------------------------------
# The scratch name is timestamped so that two concurrent drills never collide,
# and it must not resemble the database under test (guarded below).
SCRATCH_DB="${SCRATCH_ARG:-bc_restore_verify_$(date -u +%Y%m%dT%H%M%SZ)}"
case "$SCRATCH_DB" in
    *prod*|*prd*|*live*) refuse "the scratch database name '$SCRATCH_DB' looks like production — pick another name" ;;
esac
if [ "$SCRATCH_DB" = "${BACKUP_DB_NAME:-}" ]; then
    refuse "the scratch database name equals the backed-up database name '$SCRATCH_DB' — pick another name"
fi

EXISTING="$(psql -X -q -t -A -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$SCRATCH_DB'" 2>/dev/null || true)"
[ -z "$EXISTING" ] || refuse "scratch database '$SCRATCH_DB' already exists — drop it first or choose another name"

log "restoring into scratch database '$SCRATCH_DB'"
# BC_ENV_FILE is pointed at an empty file so that restore.sh does not re-source
# the operator's env file: this script has already resolved every value and
# passes them down explicitly (including the same production guard).
EMPTY_ENV_FILE="$(mktemp "${TMPDIR:-/tmp}/bc-empty-env.XXXXXX")"
RESTORE_OUTPUT="$(CONFIRM=yes BC_ENV_FILE="$EMPTY_ENV_FILE" \
    RESTORE_DB_HOST="$TARGET_HOST" RESTORE_DB_PORT="$TARGET_PORT" \
    RESTORE_DB_USER="$TARGET_USER" RESTORE_DB_PASSWORD="$TARGET_PASSWORD" \
    "${SCRIPT_DIR}/restore.sh" "$DUMP_FILE" "$SCRATCH_DB" 2>&1)" || STATUS=$?
STATUS="${STATUS:-0}"
rm -f "$EMPTY_ENV_FILE"
if [ "$STATUS" -ne 0 ]; then
    printf '%s\n' "$RESTORE_OUTPUT" >&2
    fail_report "the restore itself FAILED (exit $STATUS) — the backup is not restorable"
fi
SCRATCH_DB_CREATED=yes
printf '%s\n' "$RESTORE_OUTPUT" | sed 's/^/    /'

# -----------------------------------------------------------------------------
# 6. Integrity checks
# -----------------------------------------------------------------------------
PASS_COUNT=0
FAIL_COUNT=0
REPORT=""

# NOTE: the report lines are appended with a real printf (not `$(printf ...)`),
# because command substitution strips the trailing newline and would glue every
# check result onto a single line.
check_pass() { PASS_COUNT=$((PASS_COUNT + 1)); printf -v REPORT '%sPASS  %s\n' "$REPORT" "$1"; }
check_fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); printf -v REPORT '%sFAIL  %s\n' "$REPORT" "$1"; }
check_note() { printf -v REPORT '%sNOTE  %s\n' "$REPORT" "$1"; }

q() { psql -X -q -t -A -d "$SCRATCH_DB" -c "$1" 2>/dev/null || true; }

# The scratch database is dropped on every exit path (success, failed check, or
# an unexpected error), so an aborted drill never leaves clutter behind.
SCRATCH_DB_CREATED=no
cleanup_scratch() {
    if [ "$SCRATCH_DB_CREATED" = yes ] && [ "${KEEP:-no}" != "yes" ]; then
        dropdb --if-exists "$SCRATCH_DB" >/dev/null 2>&1 || true
    fi
}
trap 'cleanup_scratch' EXIT

fail_report() {
    printf '\n%s: FAIL — %s\n' "$PROG" "$*" >&2
    exit 1
}

# --- 6a. table count ---------------------------------------------------------
ACTUAL_TABLES="$(q "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")"
ACTUAL_TABLES="${ACTUAL_TABLES:-0}"
if [ -n "$EXPECTED_TABLES" ] && [ "$EXPECTED_TABLES" != "unknown" ]; then
    if [ "$ACTUAL_TABLES" = "$EXPECTED_TABLES" ]; then
        check_pass "table count: $ACTUAL_TABLES / $EXPECTED_TABLES expected"
    else
        check_fail "table count mismatch: restored $ACTUAL_TABLES, backup recorded $EXPECTED_TABLES"
    fi
else
    if [ "$ACTUAL_TABLES" -gt 0 ] 2>/dev/null; then
        check_pass "table count: $ACTUAL_TABLES (no recorded expectation — metadata.txt missing or incomplete)"
    else
        check_fail "the restored database contains no tables"
    fi
fi

# --- 6b. key tables exist and their row counts match the archive -------------
MISSING_TABLES=""
ROWMISMATCH=""
ROWSCHECKED=0
for table in $KEY_TABLES; do
    case " $OPTIONAL_TABLES " in
        *" $table "*) OPTIONAL=yes ;;
        *)            OPTIONAL=no ;;
    esac
    EXISTS="$(q "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='$table'")"
    if [ "$EXISTS" != "1" ]; then
        if [ "$OPTIONAL" = yes ]; then
            continue
        fi
        MISSING_TABLES="${MISSING_TABLES} ${table}"
        continue
    fi
    EXPECTED_ROW="$(awk -v t="$table" '$1 == t { print $2; found=1 } END { if (!found) print "0" }' "$EXPECTED_ROWS_FILE")"
    ACTUAL_ROW="$(q "SELECT count(*) FROM \"$table\"")"
    ACTUAL_ROW="${ACTUAL_ROW:-0}"
    ROWSCHECKED=$((ROWSCHECKED + 1))
    if [ "$ACTUAL_ROW" != "$EXPECTED_ROW" ]; then
        ROWMISMATCH="${ROWMISMATCH} ${table}(expected ${EXPECTED_ROW}, got ${ACTUAL_ROW})"
    fi
done

if [ -n "${MISSING_TABLES// /}" ]; then
    check_fail "key table(s) missing after restore:${MISSING_TABLES}"
else
    check_pass "all key tables exist after restore"
fi

if [ -n "${ROWMISMATCH// /}" ]; then
    check_fail "row count mismatch vs the archive:${ROWMISMATCH}"
else
    check_pass "row counts match the archive for $ROWSCHECKED key table(s)"
fi

# --- 6c. Flyway schema history ----------------------------------------------
HISTORY_EXISTS="$(q "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='flyway_schema_history'")"
if [ "$HISTORY_EXISTS" = "1" ]; then
    HISTORY_TOTAL="$(q "SELECT count(*) FROM flyway_schema_history")"
    HISTORY_OK="$(q "SELECT count(*) FROM flyway_schema_history WHERE success")"
    HISTORY_HEAD="$(q "SELECT COALESCE(max(NULLIF(regexp_replace(version, '\\D.*\$', ''), '')::int)::text, 'none') FROM flyway_schema_history WHERE success")"
    HISTORY_MISSING="$(q "SELECT count(*) FROM (SELECT installed_rank, row_number() OVER (ORDER BY installed_rank) AS rn FROM flyway_schema_history) s WHERE installed_rank <> rn")"
    HISTORY_DUPES="$(q "SELECT count(*) FROM (SELECT version FROM flyway_schema_history WHERE version IS NOT NULL GROUP BY version HAVING count(*) > 1) d")"

    if [ "$HISTORY_TOTAL" = "$HISTORY_OK" ] && [ "$HISTORY_TOTAL" != "0" ]; then
        check_pass "flyway_schema_history: $HISTORY_OK/$HISTORY_TOTAL migration(s) recorded as successful"
    else
        check_fail "flyway_schema_history: $HISTORY_OK of $HISTORY_TOTAL migration(s) successful (a failed migration is present)"
    fi

    if [ "$HISTORY_MISSING" = "0" ]; then
        check_pass "flyway_schema_history: installed_rank sequence is contiguous"
    else
        check_fail "flyway_schema_history: $HISTORY_MISSING gap(s) in installed_rank — migration history is incomplete"
    fi

    if [ "$HISTORY_DUPES" = "0" ]; then
        check_pass "flyway_schema_history: no duplicate version numbers"
    else
        check_fail "flyway_schema_history: $HISTORY_DUPES duplicated version(s)"
    fi

    if [ -n "$EXPECTED_FLYWAY" ]; then
        if [ "$HISTORY_HEAD" = "$EXPECTED_FLYWAY" ]; then
            check_pass "flyway schema head version: $HISTORY_HEAD (as recorded at backup time)"
        else
            check_fail "flyway schema head version mismatch: restored $HISTORY_HEAD, backup recorded $EXPECTED_FLYWAY"
        fi
    else
        check_note "flyway schema head version: $HISTORY_HEAD (no recorded expectation to compare with)"
    fi
else
    check_fail "flyway_schema_history is missing after restore — the schema state cannot be verified"
fi

# --- 6d. referential integrity across ALL foreign keys -----------------------
ORPHAN_SQL="$(cat <<'SQL'
DO $$
DECLARE
    r record;
    orphans bigint;
    total bigint := 0;
    checked int := 0;
BEGIN
    FOR r IN
        SELECT c.conname,
               n.nspname  AS child_schema,
               cl.relname AS child_table,
               (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY k.ord)
                  FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS child_cols,
               pn.nspname  AS parent_schema,
               pl.relname  AS parent_table,
               (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY k.ord)
                  FROM unnest(c.confkey) WITH ORDINALITY AS k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum) AS parent_cols
          FROM pg_constraint c
          JOIN pg_class cl     ON cl.oid = c.conrelid
          JOIN pg_namespace n  ON n.oid = cl.relnamespace
          JOIN pg_class pl     ON pl.oid = c.confrelid
          JOIN pg_namespace pn ON pn.oid = pl.relnamespace
         WHERE c.contype = 'f'
           AND n.nspname = 'public'
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %I.%I ch WHERE (%s) IS NOT NULL AND NOT EXISTS (SELECT 1 FROM %I.%I pa WHERE (%s) = (%s))',
            r.child_schema, r.child_table, r.child_cols,
            r.parent_schema, r.parent_table, r.parent_cols, r.child_cols)
        INTO orphans;
        IF orphans > 0 THEN
            RAISE WARNING 'orphaned rows: %.% (%) -> %.% : % row(s)',
                r.child_schema, r.child_table, r.conname, r.parent_schema, r.parent_table, orphans;
            total := total + orphans;
        END IF;
        checked := checked + 1;
    END LOOP;
    IF total > 0 THEN
        RAISE EXCEPTION 'referential integrity violated: % orphaned row(s) across % foreign key(s)', total, checked;
    END IF;
    RAISE NOTICE 'referential integrity OK across % foreign key(s)', checked;
END
$$;
SQL
)"

FK_OUTPUT="$(psql -X -q -t -A -d "$SCRATCH_DB" -v ON_ERROR_STOP=1 -c "$ORPHAN_SQL" 2>&1 || true)"
FK_ORPHANS="$(printf '%s' "$FK_OUTPUT" | grep -c 'orphaned rows:' || true)"
FK_CHECKED="$(printf '%s' "$FK_OUTPUT" | sed -n 's/.*referential integrity OK across \([0-9]*\) foreign key(s).*/\1/p' | head -1)"

if printf '%s' "$FK_OUTPUT" | grep -q 'referential integrity violated'; then
    check_fail "referential integrity: orphaned rows found across foreign keys"
    printf '%s\n' "$FK_OUTPUT" | grep 'orphaned rows:' | sed 's/^/        /' >&2
elif printf '%s' "$FK_OUTPUT" | grep -q 'referential integrity OK'; then
    check_pass "referential integrity: no orphaned rows across ${FK_CHECKED:-all} foreign key(s)"
else
    check_fail "referential integrity: the check could not be executed (see output below)"
    printf '%s\n' "$FK_OUTPUT" | sed 's/^/        /' >&2
fi

# --- 6e. Row Level Security is still in force (migration V13) ----------------
RLS_TOTAL="$(q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity")"
RLS_POLICIES="$(q "SELECT count(*) FROM pg_policies WHERE schemaname='public'")"
if [ "${RLS_TOTAL:-0}" -gt 0 ] 2>/dev/null; then
    check_pass "row level security enabled on $RLS_TOTAL table(s) with $RLS_POLICIES policy/policies"
else
    check_fail "no table has row level security enabled after restore — tenant isolation would be lost"
fi

# --- 6f. extensions required by the schema ----------------------------------
for ext in pgcrypto citext; do
    HAS_EXT="$(q "SELECT 1 FROM pg_extension WHERE extname='$ext'")"
    if [ "$HAS_EXT" = "1" ]; then
        check_pass "extension '$ext' present"
    else
        check_fail "extension '$ext' is missing — parts of the schema (%I defaults, citext columns) will not work"
    fi
done
HAS_VECTOR="$(q "SELECT 1 FROM pg_extension WHERE extname='vector'")"
if [ "$HAS_VECTOR" = "1" ]; then
    check_pass "optional extension 'vector' (pgvector) present"
else
    check_note "optional extension 'vector' (pgvector) is absent — semantic search stays unavailable (V14 is optional)"
fi

# --- 6g. object storage -----------------------------------------------------
if [ "${SKIP_OBJECTS_CHECK:-no}" = "yes" ]; then
    check_note "object-storage check skipped (SKIP_OBJECTS_CHECK=yes)"
elif [ -n "$(ls -A "${BACKUP_DIR}/objects" 2>/dev/null || true)" ]; then
    check_pass "object-storage snapshot present: $(find "${BACKUP_DIR}/objects" -type f | wc -l | tr -d ' ') file(s)"
else
    check_fail "the backup contains NO object-storage snapshot — restoring it would leave document metadata pointing at missing blobs"
fi

# -----------------------------------------------------------------------------
# 7. Report
# -----------------------------------------------------------------------------
printf '\n'
printf '=====================================================================\n'
printf ' RESTORE VERIFICATION REPORT\n'
printf '=====================================================================\n'
printf ' backup directory   : %s\n' "$BACKUP_DIR"
printf ' dump file          : %s\n' "$DUMP_FILE"
printf ' dump size          : %s bytes\n' "$(wc -c < "$DUMP_FILE" | tr -d ' ')"
printf ' target server      : %s:%s (user %s)\n' "$TARGET_HOST" "$TARGET_PORT" "$TARGET_USER"
printf ' scratch database   : %s\n' "$SCRATCH_DB"
printf ' restore timestamp  : %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# NOTE: `printf '-----\n'` would be parsed as an option list ("--" prefix), so
# the separator lines are passed through `%s` explicitly.
printf '%s\n' '---------------------------------------------------------------------'
printf '%s\n' "$REPORT"
printf '%s\n' '---------------------------------------------------------------------'
printf ' checks passed      : %s\n' "$PASS_COUNT"
printf ' checks failed      : %s\n' "$FAIL_COUNT"
printf '=====================================================================\n'

if [ "${KEEP:-no}" = "yes" ]; then
    log "KEEP=yes — scratch database '$SCRATCH_DB' was left in place (drop it when done)"
else
    log "dropping scratch database '$SCRATCH_DB'"
    psql -X -q -t -A -d "$SCRATCH_DB" -c 'SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()' >/dev/null 2>&1 || true
    if dropdb --if-exists "$SCRATCH_DB" >/dev/null 2>&1; then
        log "scratch database dropped"
    else
        warn "could not drop scratch database '$SCRATCH_DB' — drop it manually: dropdb $SCRATCH_DB"
    fi
fi

unset PGPASSWORD

if [ "$FAIL_COUNT" -gt 0 ]; then
    printf '%s: FAIL — %s check(s) failed; this backup must NOT be trusted as restorable\n' "$PROG" "$FAIL_COUNT" >&2
    exit 1
fi

printf '%s: PASS — the backup restored cleanly and passed every integrity check\n' "$PROG"
exit 0
