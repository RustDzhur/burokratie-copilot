#!/usr/bin/env bash
# Prints the numbers the documents quote, MEASURED from the tree. Documents link here instead of copying numbers,
# because copied numbers drift (docs/completion-audit.md, M1).
#   scripts/facts.sh            structure only (fast, no build)
#   scripts/facts.sh --tests    also read the last test reports under services/api/target (run `mvnw verify` first)
set -euo pipefail
cd "$(dirname "$0")/.."
api=services/api/src/main
web=apps/web/src

echo "commit:               $(git rev-parse --short HEAD) ($(git log -1 --format=%cd --date=short))"
echo "migrations:           $(ls $api/resources/db/migration | grep -c '^V.*\.sql$')   (latest: $(ls $api/resources/db/migration | sort -V | tail -1))"
echo "controllers:          $(grep -rl '@RestController' $api/java | wc -l | tr -d ' ')"
echo "http routes:          $(grep -rhoE '@(Get|Post|Put|Patch|Delete)Mapping' $api/java | wc -l | tr -d ' ')"
echo "UI calls (resources): $(grep -cE 'client\.(get|post|put|patch|delete|list|upload|request)' $web/lib/api/resources.ts)"
echo "UI pages:             $(find $web/app -name page.tsx | wc -l | tr -d ' ')"
echo "i18n locales:         $(ls $web/i18n/messages/core | grep -c '\.ts$')"
echo "web test files:       $(find $web -name '*.test.ts' -o -name '*.test.tsx' | wc -l | tr -d ' ')"
echo "api IT classes:       $(find services/api/src/test -name '*IT.java' | wc -l | tr -d ' ')"
echo "api unit classes:     $(find services/api/src/test -name '*Test.java' | wc -l | tr -d ' ')"

if [[ "${1:-}" == "--tests" ]]; then
  sum() { # sum <attr> over report xml files
    cat "$1"/TEST-*.xml 2>/dev/null | grep -o "<testsuite [^>]*" | grep -o "$2=\"[0-9]*\"" | grep -o '[0-9]*' | paste -sd+ - | bc 2>/dev/null || echo 0; }
  for kind in surefire failsafe; do
    dir=services/api/target/$kind-reports
    echo "api $kind tests:  $(sum $dir tests) run, $(sum $dir failures) failures, $(sum $dir errors) errors"
  done
fi
