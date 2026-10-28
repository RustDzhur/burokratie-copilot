#!/usr/bin/env bash
# =============================================================================
#  check-no-env-committed.sh — fail when a secret-bearing env file is tracked
# =============================================================================
#  Spec §21/§30: real secrets must never be in the repository. This guard is run
#  by CI (job `security`) and by infra/scripts/check-structure.sh.
#
#  It fails when git tracks a file that:
#    * is named `.env`, `.env.<anything>` or `*.env` (but NOT `*.env.example` /
#      `.env.example`, which are templates and must be committed), or
#    * matches the private-key patterns .pem/.key/.p12/.jks/.keystore inside the
#      repository — those must live outside the working tree or in a secret store.
#
#  Usage:
#      infra/scripts/check-no-env-committed.sh            # tracked files only
#      infra/scripts/check-no-env-committed.sh --all      # also untracked files
#
#  Exit codes: 0 = clean, 1 = violation found, 2 = usage error / not a repo.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROG="$(basename "$0")"

SCAN_ALL=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            cat <<'USAGE'
Usage: check-no-env-committed.sh [--all]

  --all   also inspect untracked (not ignored) files, not only tracked ones

Exit codes: 0 = clean, 1 = violation, 2 = usage error.
USAGE
            exit 0 ;;
        --all) SCAN_ALL=1; shift ;;
        *) echo "$PROG: unknown argument: $1" >&2; exit 2 ;;
    esac
done

cd "$REPO_ROOT"

if ! command -v git >/dev/null 2>&1; then
    echo "$PROG: git not found — cannot inspect the working tree" >&2
    exit 2
fi

if ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "$PROG: $REPO_ROOT is not a git worktree — nothing to check" >&2
    exit 2
fi

FILES="$(git ls-files)"
if [ "$SCAN_ALL" -eq 1 ]; then
    # Untracked but not ignored: `--others --exclude-standard`.
    FILES="$(printf '%s\n%s\n' "$FILES" "$(git ls-files --others --exclude-standard)")"
fi
FILES="$(printf '%s\n' "$FILES" | sed '/^$/d' | sort -u)"

VIOLATIONS=""

# 1. env files that could hold real values.
ENV_HITS="$(printf '%s\n' "$FILES" \
    | grep -E '(^|/)\.env(\.[^/]*)?$|(^|/)[^/]*\.env$' \
    | grep -v -E '\.example$' || true)"
[ -z "$ENV_HITS" ] || VIOLATIONS="${VIOLATIONS}${ENV_HITS}
"

# 2. private key material.
KEY_HITS="$(printf '%s\n' "$FILES" \
    | grep -E '\.(pem|key|p12|pfx|jks|keystore|ppk)$' \
    | grep -v -E '(^|/)src/test/resources/.*\.pem$' || true)"
[ -z "$KEY_HITS" ] || VIOLATIONS="${VIOLATIONS}${KEY_HITS}
"

# 3. A .gitignore that no longer ignores .env would let the next commit slip one
#    in; treat that as a violation too.
if [ -f .gitignore ]; then
    if ! grep -qE '^!?\.env' .gitignore; then
        VIOLATIONS="${VIOLATIONS}.gitignore does not ignore .env
"
    fi
else
    VIOLATIONS="${VIOLATIONS}.gitignore is missing
"
fi

if [ -n "${VIOLATIONS//[[:space:]]/}" ]; then
    printf '%s: FAIL — files that must not be committed are present:\n' "$PROG" >&2
    printf '%s\n' "$VIOLATIONS" | sed '/^$/d' | sed 's/^/  - /' >&2
    cat >&2 <<'HINT'

  Fix:
    * remove the file from the index:  git rm --cached <file>
    * keep the real values OUTSIDE the repository (secret manager, SOPS, Docker
      secrets) and commit only *.env.example templates with placeholders;
    * if a secret has already been pushed, treat it as compromised: rotate it
      (see docs/deployment.md → "Secrets management"), then rewrite history.
HINT
    exit 1
fi

printf '%s: OK — no .env file, private key or missing .gitignore rule found (%s file(s) inspected)\n' \
    "$PROG" "$(printf '%s\n' "$FILES" | wc -l | tr -d ' ')"
exit 0
