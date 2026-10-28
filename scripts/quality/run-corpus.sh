#!/usr/bin/env bash
#
# Reproducible document-quality run.
#
# It generates (or reuses) the synthetic corpus in tests/corpus/documents, runs the
# real pipeline through DocumentCorpusQualityIT against a real PostgreSQL, and
# regenerates docs/quality/DOCUMENT_QUALITY.md.
#
# Why a clean copy of services/api: at the time of the first measurement the working
# tree contained accidental duplicate files named "* 2.java" / "* 2.sql". They were
# untracked and break javac ("duplicate class") and Flyway ("more than one migration").
# Copying the module without them keeps this stand reproducible without touching
# anybody else's files and isolates it from stale target/ output. The copy costs little
# and is harmless in a clean checkout.
#
# Usage:
#   scripts/quality/run-corpus.sh                     # dev + holdout, current code
#   scripts/quality/run-corpus.sh --regenerate        # regenerate the corpus first
#   scripts/quality/run-corpus.sh --split dev         # one split
#   scripts/quality/run-corpus.sh --no-russian        # skip the rus OCR experiment
#   BC_TEST_DB_NAME=my_quality_db scripts/quality/run-corpus.sh
#   scripts/quality/run-corpus.sh --reuse-db   # reuse the DB (e.g. for debugging)
#   BC_KEEP_QUALITY_DB=1 scripts/quality/run-corpus.sh   # keep the temporary DB
#
# Requirements: bash, JDK 21, Maven wrapper, PostgreSQL reachable with the test
# profile defaults, Python 3 with Pillow/OpenCV/NumPy, Tesseract with deu data.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_SRC="$ROOT/services/api"
WORK="${BC_QUALITY_WORK:-$ROOT/target/quality/api-worktree}"
RESULTS="$ROOT/tests/corpus/results"
MANIFEST="$ROOT/tests/corpus/documents/manifest.json"
REPORT="$ROOT/docs/quality/DOCUMENT_QUALITY.md"
DB="${BC_TEST_DB_NAME:-burokratie_quality}"
DB_USER="${BC_TEST_DB_USER:-burokratie}"
LABEL="${CORPUS_LABEL:-final}"
SPLITS="${CORPUS_SPLITS:-dev holdout}"
REGENERATE=0
WITH_RUSSIAN=1
REUSE_DB=0
LIMIT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --regenerate) REGENERATE=1; shift ;;
    --no-russian) WITH_RUSSIAN=0; shift ;;
    --split) SPLITS="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --db) DB="$2"; shift 2 ;;
    --reuse-db) REUSE_DB=1; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${JAVA_HOME:-}" && -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
fi
if ! command -v tesseract >/dev/null 2>&1; then
  echo "tesseract is required (brew install tesseract tesseract-lang)" >&2
  exit 2
fi
if ! tesseract --list-langs 2>/dev/null | grep -qx "deu"; then
  echo "tesseract language data 'deu' is missing (brew install tesseract-lang)" >&2
  exit 2
fi
HAS_RUS=0
if tesseract --list-langs 2>/dev/null | grep -qx "rus"; then
  HAS_RUS=1
fi
if [[ "$WITH_RUSSIAN" == "1" && "$HAS_RUS" == "0" ]]; then
  echo "warning: tesseract language data 'rus' is missing; the Russian experiment is skipped" >&2
  WITH_RUSSIAN=0
fi

case "$WORK" in
  "/"|""|"$ROOT") echo "refusing unsafe work directory: '$WORK'" >&2; exit 2 ;;
esac
mkdir -p "$RESULTS" "$(dirname "$REPORT")"

if [[ "$REGENERATE" == "1" || ! -f "$MANIFEST" ]]; then
  echo "== generating corpus"
  python3 "$ROOT/scripts/quality/generate_corpus.py" --split all --clean
fi

# -- database -------------------------------------------------------------------------
# A fresh database per run: the test DB is shared with other integration tests, and a
# parallel migration (e.g. V81) can change its checksum while a long OCR run is going.
# The run database is dropped at exit unless BC_KEEP_QUALITY_DB=1 or --reuse-db is given.
RUN_DB="$DB"
if [[ "$REUSE_DB" == "0" ]]; then
  RUN_DB="${DB}_run_$(date +%s)_$$"
  if command -v createdb >/dev/null 2>&1; then
    echo "== creating temporary database $RUN_DB (owner $DB_USER)"
    if ! createdb -O "$DB_USER" "$RUN_DB"; then
      echo "could not create $RUN_DB; falling back to existing $DB" >&2
      RUN_DB="$DB"
    else
      if [[ "${BC_KEEP_QUALITY_DB:-0}" != "1" ]]; then
        # shellcheck disable=SC2064
        trap 'dropdb --if-exists "$RUN_DB" >/dev/null 2>&1 || true' EXIT
      else
        echo "   keeping $RUN_DB (BC_KEEP_QUALITY_DB=1)"
      fi
    fi
  fi
fi
if [[ "$RUN_DB" == "$DB" ]]; then
  if command -v psql >/dev/null 2>&1; then
    if ! psql -d postgres -Atc "select 1 from pg_database where datname='$DB'" 2>/dev/null | grep -qx 1; then
      echo "== creating database $DB (owner $DB_USER)"
      createdb -O "$DB_USER" "$DB" || {
        echo "could not create $DB. Create it once with:" >&2
        echo "  createdb -O $DB_USER $DB" >&2
        exit 2
      }
    fi
  else
    echo "psql not found; assuming $DB exists and is reachable" >&2
  fi
fi

# -- clean working copy of the API ----------------------------------------------------
echo "== preparing clean API copy at $WORK"
rm -rf "$WORK"
mkdir -p "$WORK"
# --exclude '* 2.*' removes the accidental duplicate files described in the header.
rsync -a --exclude 'target/' --exclude '* 2.*' --exclude '.DS_Store' \
  "$API_SRC/" "$WORK/"

run_split() {
  local split="$1"
  local label="$2"
  local extra="${3:-}"
  local log="$ROOT/target/quality/log-$label-$split.log"
  mkdir -p "$(dirname "$log")"
  echo "== pipeline: split=$split label=$label"
  (
    cd "$WORK"
    BC_TEST_DB_NAME="$RUN_DB" JAVA_HOME="${JAVA_HOME:-}" \
      ./mvnw -q test-compile failsafe:integration-test failsafe:verify \
        -Dit.test=DocumentCorpusQualityIT \
        -DfailIfNoTests=false \
        -Dcorpus.split="$split" \
        -Dcorpus.label="$label-$split" \
        -Dcorpus.manifest="$MANIFEST" \
        ${LIMIT:+-Dcorpus.limit="$LIMIT"} \
        $extra
  ) 2>&1 | tee "$log"
  if [[ ! -f "$WORK/target/quality/results-$label-$split.json" ]]; then
    echo "the run produced no result file for $label/$split (see $log)" >&2
    exit 1
  fi
  cp "$WORK/target/quality/results-$label-$split.json" "$RESULTS/$label-$split.json"
}

for split in $SPLITS; do
  run_split "$split" "$LABEL"
done

if [[ "$WITH_RUSSIAN" == "1" ]]; then
  for split in $SPLITS; do
    run_split "$split" "ru" "-Dcorpus.difficulty=russian -Dcorpus.ocr.languages=deu+eng+rus"
  done
fi

if [[ -f "$RESULTS/$LABEL-dev.json" && -f "$RESULTS/$LABEL-holdout.json" ]]; then
  echo "== report"
  python3 "$ROOT/scripts/quality/report.py" \
    --results-dir "$RESULTS" \
    --out "$REPORT" \
    --final-stage "$LABEL"
else
  echo "== report skipped: both $LABEL-dev and $LABEL-holdout are needed"
fi

echo
echo "results: $RESULTS"
echo "report:  $REPORT"
