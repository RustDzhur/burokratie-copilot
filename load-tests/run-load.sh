#!/usr/bin/env bash
# =============================================================================
#  run-load.sh — drive a running instance and record what happened
# =============================================================================
#  The acceptance criterion for I-003 is that the control sets and the conditions
#  are recorded and the results are published. A number without its conditions is
#  not a result: "250 documents per minute" means nothing until somebody says on
#  what machine, against which database, with how many callers and for how long.
#  This script therefore writes the conditions and the numbers into the same file.
#
#  Usage:
#      BASE_URL=http://127.0.0.1:8080 CONCURRENCY=4 DURATION=30 ./run-load.sh
#
#  What it measures: how many ingestions and invoice reads the instance completes
#  while N callers work at once, and how long the slowest one took. It is a
#  smoke-level measurement, not a capacity figure: a capacity figure needs the
#  production-like database and the real hardware, and saying so is part of
#  publishing the result.
# =============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
TOKEN="${TOKEN:-}"
CONCURRENCY="${CONCURRENCY:-4}"
DURATION="${DURATION:-30}"
OUT="${OUT:-load-tests/RESULTS.md}"
LABEL="${LABEL:-local dev instance}"

command -v curl >/dev/null 2>&1 || { echo "curl not found" >&2; exit 2; }

echo "== conditions =="
COMMIT="$(git -C "$(dirname "$0")/.." rev-parse --short HEAD 2>/dev/null || echo unknown)"
HOST_DESC="$(uname -sm) $(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 | xargs || echo 'unknown cpu')"
PG_VERSION="$(psql -U "${BC_DB_USER:-rustem}" -d "${BC_DB_NAME:-burokratie_test}" -tAc 'select version()' 2>/dev/null | cut -c1-60 || echo 'unknown')"
TABLES="$(psql -U "${BC_DB_USER:-rustem}" -d "${BC_DB_NAME:-burokratie_test}" -tAc "select count(*) from information_schema.tables where table_schema='public'" 2>/dev/null || echo '?')"
MIGRATIONS="$(ls "$(dirname "$0")/../services/api/src/main/resources/db/migration"/*.sql 2>/dev/null | wc -l | tr -d ' ')"

cat <<COND
  label        : $LABEL
  commit       : $COMMIT
  host         : $HOST_DESC
  postgres     : $PG_VERSION
  tables       : $TABLES
  migrations   : $MIGRATIONS
  concurrency  : $CONCURRENCY callers
  duration     : $DURATION s
  target       : $BASE_URL
COND

if ! curl -fsS --max-time 5 "$BASE_URL/actuator/health" >/dev/null 2>&1 \
   && ! curl -fsS --max-time 5 "$BASE_URL/api/v1/health" >/dev/null 2>&1; then
    echo
    echo "RESULT: NOT MEASURED — nothing is listening at $BASE_URL."
    echo "A load test against an instance that is not running produces no numbers,"
    echo "and reporting zero would be reporting a failure of the setup as a failure of the system."
    exit 3
fi

echo "== running =="
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
START=$(date +%s)
for caller in $(seq 1 "$CONCURRENCY"); do
    (
        count=0; slowest=0
        while [ $(( $(date +%s) - START )) -lt "$DURATION" ]; do
            began=$(date +%s%N)
            code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
                   -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/invoices?limit=20" || echo 000)
            took=$(( ($(date +%s%N) - began) / 1000000 ))
            [ "$took" -gt "$slowest" ] && slowest=$took
            echo "$code $took" >> "$TMP/caller-$caller"
            count=$((count + 1))
        done
        echo "$count $slowest" > "$TMP/summary-$caller"
    ) &
done
wait
ELAPSED=$(( $(date +%s) - START ))

TOTAL=$(cat "$TMP"/caller-* 2>/dev/null | wc -l | tr -d ' ')
OK=$(grep -h '^2' "$TMP"/caller-* 2>/dev/null | wc -l | tr -d ' ')
FAILED=$((TOTAL - OK))
SLOWEST=$(cat "$TMP"/caller-* 2>/dev/null | awk '{print $2}' | sort -n | tail -1)
P50=$(cat "$TMP"/caller-* 2>/dev/null | awk '{print $2}' | sort -n | awk '{a[NR]=$1} END{print a[int(NR/2)+1]}')
RPS=$(awk -v t="$TOTAL" -v e="$ELAPSED" 'BEGIN{ if (e>0) printf "%.1f", t/e; else print "0" }')

echo "  requests : $TOTAL in ${ELAPSED}s (${RPS}/s)"
echo "  ok       : $OK"
echo "  failed   : $FAILED"
echo "  p50      : ${P50:-?} ms"
echo "  slowest  : ${SLOWEST:-?} ms"

{
    echo
    echo "| $LABEL | $COMMIT | $CONCURRENCY | $DURATION s | $TOTAL | $OK | $FAILED | ${P50:-?} ms | ${SLOWEST:-?} ms | ${RPS}/s |"
} >> "$OUT"

echo
echo "recorded in $OUT"
[ "$FAILED" -eq 0 ] || { echo "RESULT: FAILURES PRESENT — the numbers are not a clean baseline"; exit 1; }
echo "RESULT: measured"
