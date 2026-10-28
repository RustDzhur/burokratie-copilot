#!/usr/bin/env bash
# Fails when a document names a test class (`FooIT`, `FooTest`) that does not exist in the tree.
# docs/completion-audit.md (M1) found 27 of 50 names in the requirements matrix pointing at nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
missing=0
for doc in docs/requirements-matrix.md docs/final-report.md; do
  [[ -f "$doc" ]] || continue
  for name in $(grep -oE '`[A-Z][A-Za-z0-9]*(IT|Test)`' "$doc" | tr -d '`' | sort -u); do
    if ! find services apps -name "$name.java" -o -name "$name.ts" -o -name "$name.tsx" 2>/dev/null | grep -q .; then
      echo "$doc: no such test class: $name"; missing=$((missing+1))
    fi
  done
done
[[ $missing -eq 0 ]] && echo "check-doc-references: OK" || { echo "check-doc-references: $missing missing"; exit 1; }
