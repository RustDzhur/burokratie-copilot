#!/usr/bin/env bash
# =============================================================================
#  run-load-test.sh — run a k6 scenario and record its conditions
# =============================================================================
#  A performance number without its test conditions is not evidence (spec §27), so
#  this wrapper writes the effective conditions next to every result:
#
#      tests/load/results/<UTC timestamp>/
#          conditions.txt        effective environment + k6 version + git revision
#          <scenario>-summary.json  k6 summary export (metrics)
#          <scenario>-output.txt    full k6 output
#
#  Usage:
#      tests/load/run-load-test.sh smoke|upload|list
#      BASE_URL=http://localhost:8080 tests/load/run-load-test.sh upload
#      BC_LOAD_AUTH_MODE=token BC_LOAD_TOKEN=... tests/load/run-load-test.sh list
#
#  Exit codes: 0 = thresholds passed, 1 = k6 failed or a threshold was breached,
#              2 = refused / usage error, 3 = k6 is not installed.
#
#  Compatible with bash 3.2 (macOS): no associative arrays, no ${var,,}.
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
Usage: run-load-test.sh [smoke|upload|list] [extra k6 arguments...]

Scenarios:
  smoke   1 VU / 20 s sanity check of the scripts and the endpoints (default)
  upload  document upload load profile (BC_LOAD_VUS, BC_LOAD_HOLD, ...)
  list    document list load profile (BC_LOAD_PAGE_SIZE, ...)

Key environment variables (all optional except BASE_URL for a non-local target):
  BASE_URL                 target backend, default http://localhost:8080
  BC_LOAD_AUTH_MODE        none | token | cookie | login   (default none)
  BC_LOAD_TOKEN            personal API token for auth mode `token`
  BC_LOAD_USERNAME/PASSWORD  credentials for auth mode `login`
  BC_LOAD_VUS, BC_LOAD_HOLD, BC_LOAD_RAMP_UP, BC_LOAD_THINK_TIME
  BC_LOAD_PAGE_SIZE        list endpoint page size
  BC_LOAD_FILE_SIZE_KB     synthetic payload size when no fixture is provided
  BC_LOAD_FIXTURE_DIR      directory holding the real fixture file
  I_KNOW_THIS_IS_PRODUCTION=yes   allow a non-local BASE_URL

Exit codes: 0 = passed, 1 = failed/threshold breached, 2 = refused, 3 = no k6.
USAGE
}

SCENARIO="smoke"
if [ "$#" -gt 0 ]; then
    case "$1" in
        -h|--help) usage; exit 0 ;;
        smoke|upload|list) SCENARIO="$1"; shift ;;
        -*) : ;;   # first argument is already a k6 flag: keep the default scenario
        *)  echo "$PROG: unknown scenario '$1' (expected smoke|upload|list)" >&2; usage >&2; exit 2 ;;
    esac
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
if [ -z "$BASE_URL" ]; then
    refuse "BASE_URL must not be empty"
fi

# -----------------------------------------------------------------------------
# Production guard
# -----------------------------------------------------------------------------
# Applying load to a production system is a denial-of-service attack unless it is
# a planned exercise. Only the obvious local/private targets are allowed by
# default.
BASE_HOST="$(printf '%s' "$BASE_URL" | sed -e 's|^[a-zA-Z][a-zA-Z0-9+.-]*://||' -e 's|/.*$||' -e 's|:.*$||')"
LOOKS_PRODUCTION=no
case "$BASE_HOST" in
    localhost|127.0.0.1|0.0.0.0|::1|'') : ;;
    *.local|*.localhost|*.internal|*.test|*.lan) : ;;
    10.*|192.168.*) : ;;
    172.1[6-9].*|172.2[0-9].*|172.3[01].*) : ;;
    *prod*|*Prod*|*prd*|*live*|*Live*) LOOKS_PRODUCTION=yes ;;
    *) LOOKS_PRODUCTION=yes ;;
esac
if [ "$LOOKS_PRODUCTION" = yes ]; then
    warn "BASE_URL '$BASE_URL' does not look like a local environment"
    if [ "${I_KNOW_THIS_IS_PRODUCTION:-no}" != "yes" ]; then
        refuse "load-testing a non-local target requires I_KNOW_THIS_IS_PRODUCTION=yes — a load test against production is an outage risk unless it is planned and agreed"
    fi
    warn "proceeding because I_KNOW_THIS_IS_PRODUCTION=yes — make sure the owner agreed to this window"
fi

# -----------------------------------------------------------------------------
# k6 availability (native binary, else container when Docker exists)
# -----------------------------------------------------------------------------
K6_CMD=""
K6_MODE=""
K6_VERSION="unavailable"
if command -v k6 >/dev/null 2>&1; then
    K6_CMD="k6"
    K6_MODE="native"
    K6_VERSION="$(k6 version 2>/dev/null | head -1 || echo unknown)"
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    K6_CMD="docker"
    K6_MODE="docker"
    K6_VERSION="grafana/k6:latest (container)"
    warn "k6 is not installed; falling back to the grafana/k6 container image"
else
    cat >&2 <<EOF

$PROG: ERROR (exit 3) — k6 is NOT installed and no usable Docker daemon is available.

  Install k6 (pick one):
      brew install k6                                  # macOS
      sudo apt-get install k6                          # Debian/Ubuntu (after adding the repo)
      https://grafana.com/docs/k6/latest/set-up/install-k6/

  Then re-run:
      $PROG $SCENARIO

  Nothing was executed. No performance numbers exist until a real run happens —
  do not report figures that were never measured (spec §27).

EOF
    exit 3
fi

# -----------------------------------------------------------------------------
# Results directory
# -----------------------------------------------------------------------------
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RESULTS_DIR="${BC_LOAD_RESULTS_DIR:-${SCRIPT_DIR}/results}/${RUN_ID}"
mkdir -p "$RESULTS_DIR"
SUMMARY_FILE="${RESULTS_DIR}/${SCENARIO}-summary.json"
OUTPUT_FILE="${RESULTS_DIR}/${SCENARIO}-output.txt"

SCRIPT_FILE="${SCRIPT_DIR}/k6/${SCENARIO}.js"
[ -f "$SCRIPT_FILE" ] || die "scenario script not found: $SCRIPT_FILE"

# -----------------------------------------------------------------------------
# Record the conditions (never a secret)
# -----------------------------------------------------------------------------
mask() {
    # mask <value> — show only the length so that no credential is written down
    if [ -z "$1" ]; then printf '<unset>'; else printf '<set:%s chars>' "$(printf '%s' "$1" | wc -c | tr -d ' ')"; fi
}

GIT_REV="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>/dev/null || true)"
case "$GIT_REV" in ''|HEAD|*' '*) GIT_REV="no-commit" ;; esac
GIT_BRANCH="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
case "$GIT_BRANCH" in ''|HEAD|*' '*) GIT_BRANCH="no-branch" ;; esac

{
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'timestamp_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'scenario=%s\n' "$SCENARIO"
    printf 'script=%s\n' "tests/load/k6/${SCENARIO}.js"
    printf 'operator=%s\n' "$(id -un 2>/dev/null || echo unknown)"
    printf 'host=%s\n' "$(hostname 2>/dev/null || echo unknown)"
    printf 'git_revision=%s\n' "$GIT_REV"
    printf 'git_branch=%s\n' "$GIT_BRANCH"
    printf 'k6_mode=%s\n' "$K6_MODE"
    printf 'k6_version=%s\n' "$K6_VERSION"
    printf 'base_url=%s\n' "$BASE_URL"
    printf 'base_host=%s\n' "$BASE_HOST"
    printf 'auth_mode=%s\n' "${BC_LOAD_AUTH_MODE:-none}"
    printf 'token=%s\n' "$(mask "${BC_LOAD_TOKEN:-}")"
    printf 'password=%s\n' "$(mask "${BC_LOAD_PASSWORD:-}")"
    printf 'cookie_provided=%s\n' "$([ -n "${BC_LOAD_COOKIE:-}" ] && echo yes || echo no)"
    printf 'username=%s\n' "${BC_LOAD_USERNAME:-<unset>}"
    printf 'tenant=%s\n' "${BC_LOAD_TENANT:-<unset>}"
    printf 'vus=%s\n' "${BC_LOAD_VUS:-10}"
    printf 'max_vus=%s\n' "${BC_LOAD_MAX_VUS:-25}"
    printf 'ramp_up=%s\n' "${BC_LOAD_RAMP_UP:-30s}"
    printf 'hold=%s\n' "${BC_LOAD_HOLD:-2m}"
    printf 'ramp_down=%s\n' "${BC_LOAD_RAMP_DOWN:-15s}"
    printf 'think_time_s=%s\n' "${BC_LOAD_THINK_TIME:-1}"
    printf 'page_size=%s\n' "${BC_LOAD_PAGE_SIZE:-25}"
    printf 'file_size_kb=%s\n' "${BC_LOAD_FILE_SIZE_KB:-512}"
    printf 'fixture_dir=%s\n' "${BC_LOAD_FIXTURE_DIR:-<none: synthetic payload>}"
    printf 'path_upload=%s\n' "${BC_LOAD_PATH_UPLOAD:-/api/v1/documents}"
    printf 'path_list=%s\n' "${BC_LOAD_PATH_LIST:-/api/v1/documents}"
    printf 'threshold_upload_p95_ms=%s\n' "${BC_LOAD_UPLOAD_P95_MS:-5000}"
    printf 'threshold_upload_p99_ms=%s\n' "${BC_LOAD_UPLOAD_P99_MS:-10000}"
    printf 'threshold_list_p95_ms=%s\n' "${BC_LOAD_LIST_P95_MS:-1500}"
    printf 'threshold_list_p99_ms=%s\n' "${BC_LOAD_LIST_P99_MS:-3000}"
    printf 'threshold_max_error_rate=%s\n' "${BC_LOAD_MAX_ERROR_RATE:-0.01}"
    printf 'threshold_min_checks_rate=%s\n' "${BC_LOAD_MIN_CHECKS_RATE:-0.99}"
    printf '# NOTE: backend/database/storage conditions (versions, dataset size, OCR/AI on or off,\n'
    printf '#       JVM flags, host specs) are NOT visible from here. Complete\n'
    printf '#       tests/load/conditions.template.md before using this run as evidence.\n'
    printf '# NOTE: this file intentionally contains no credential values.\n'
} > "${RESULTS_DIR}/conditions.txt"

log "results directory: $RESULTS_DIR"
log "conditions written to ${RESULTS_DIR}/conditions.txt"
if [ -n "${BC_LOAD_FIXTURE_DIR:-}" ]; then
    log "fixture directory: $BC_LOAD_FIXTURE_DIR (payload identity is recorded as provided)"
else
    warn "BC_LOAD_FIXTURE_DIR is not set: the upload scenario uses a SYNTHETIC $(printf '%s' "${BC_LOAD_FILE_SIZE_KB:-512}")KB PDF."
    warn "Numbers from a synthetic payload are not comparable with a real scanned document."
fi

# -----------------------------------------------------------------------------
# Run
# -----------------------------------------------------------------------------
K6_ARGS=(run "--summary-export=${SUMMARY_FILE}" "$@" "$SCRIPT_FILE")

log "running: $SCENARIO ($K6_MODE)"
set +e
if [ "$K6_MODE" = "native" ]; then
    ( cd "$REPO_ROOT" && k6 "${K6_ARGS[@]}" ) 2>&1 | tee "$OUTPUT_FILE"
    STATUS=${PIPESTATUS[0]}
else
    # Mount the repository so that open() can read fixtures, and forward the
    # BC_LOAD_*/BASE_URL variables into the container.
    ( cd "$REPO_ROOT" && docker run --rm --network host \
        -v "${REPO_ROOT}:/work" -w /work \
        -e BASE_URL -e BC_LOAD_AUTH_MODE -e BC_LOAD_TOKEN -e BC_LOAD_COOKIE \
        -e BC_LOAD_USERNAME -e BC_LOAD_PASSWORD -e BC_LOAD_TENANT \
        -e BC_LOAD_VUS -e BC_LOAD_MAX_VUS -e BC_LOAD_HOLD -e BC_LOAD_RAMP_UP \
        -e BC_LOAD_RAMP_DOWN -e BC_LOAD_THINK_TIME -e BC_LOAD_PAGE_SIZE \
        -e BC_LOAD_FILE_SIZE_KB -e BC_LOAD_FIXTURE_DIR -e BC_LOAD_PATH_UPLOAD \
        -e BC_LOAD_PATH_LIST \
        grafana/k6:latest "${K6_ARGS[@]}" ) 2>&1 | tee "$OUTPUT_FILE"
    STATUS=${PIPESTATUS[0]}
fi
set -e

# -----------------------------------------------------------------------------
# Result
# -----------------------------------------------------------------------------
if [ "$STATUS" -eq 0 ]; then
    log "PASS — every threshold was met"
    log "summary: $SUMMARY_FILE"
    log "next: complete tests/load/conditions.template.md and attach it to the result"
    exit 0
fi

warn "k6 exited with status $STATUS — a threshold was breached or the run failed"
warn "output: $OUTPUT_FILE"
warn "Do NOT report these numbers as a passing performance result."
exit 1
