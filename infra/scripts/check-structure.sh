#!/usr/bin/env bash
# =============================================================================
#  check-structure.sh — assert the repository layout required by spec §2.2
# =============================================================================
#  Run it from anywhere; paths are resolved relative to the repository root
#  (the parent of this script's directory).
#
#      infra/scripts/check-structure.sh
#      infra/scripts/check-structure.sh --strict   # PENDING items fail as well
#      infra/scripts/check-structure.sh --quiet    # only problems + summary
#
#  Exit codes:
#      0  every REQUIRED path exists and no guard tripped
#      1  a required path is missing, or a guard tripped (committed .env,
#         non-executable script, malformed YAML, ...)
#      2  usage error
#
#  Why "PENDING" exists: spec §2.2 lists a target layout, and parts of it are
#  built by other workstreams in parallel. Reporting a not-yet-created part as a
#  hard failure would make this check useless during development; reporting it as
#  "present" would be a lie. PENDING is printed explicitly and, with --strict,
#  turned into a failure — so a release build can demand the complete layout.
#
#  The YAML check is delegated to python3 (PyYAML) when available, because a
#  structural YAML error in a Compose file is exactly the class of defect that
#  this script must catch. Without python3 the check is reported as SKIPPED
#  rather than silently passed.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$REPO_ROOT"

PROG="$(basename "$0")"
STRICT=0
QUIET=0
ALLOW_WEB_PENDING=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --strict) STRICT=1; shift ;;
        --quiet)  QUIET=1; shift ;;
        --allow-web-pending)
            # The frontend is owned by a parallel workstream. With this flag a
            # missing `output: "standalone"` (or a missing lockfile) is reported
            # as PENDING instead of FAIL, so this script can be used as a CI gate
            # while that work is still in flight. The default stays strict: a
            # release must not be cut with a broken frontend image.
            ALLOW_WEB_PENDING=1; shift ;;
        -h|--help)
            cat <<'USAGE'
Usage: check-structure.sh [--strict] [--quiet]

  --strict   treat PENDING (planned but not yet created) paths as failures
  --quiet    print only problems and the summary
  --allow-web-pending
             report a not-yet-correct apps/web configuration (missing
             output: "standalone", missing lockfile) as PENDING instead of FAIL

Exit codes: 0 = ok, 1 = structure problem, 2 = usage error.
USAGE
            exit 0 ;;
        *) echo "$PROG: unknown argument: $1" >&2; exit 2 ;;
    esac
done

FAILURES=0
PENDING=0
CHECKS=0

ok()      { CHECKS=$((CHECKS + 1)); [ "$QUIET" -eq 1 ] || printf '  \033[32mOK\033[0m       %s\n' "$1"; }
pending() { CHECKS=$((CHECKS + 1)); PENDING=$((PENDING + 1)); printf '  \033[33mPENDING\033[0m  %s\n' "$1"; }
fail()    { CHECKS=$((CHECKS + 1)); FAILURES=$((FAILURES + 1)); printf '  \033[31mFAIL\033[0m     %s\n' "$1" >&2; }
note()    { [ "$QUIET" -eq 1 ] || printf '  ----     %s\n' "$1"; }
section() { [ "$QUIET" -eq 1 ] || printf '\n%s\n' "$1"; }

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------
require_dir() {
    # require_dir <path> <description>
    if [ -d "$1" ]; then ok "directory $1 — $2"; else fail "missing directory: $1 ($2)"; fi
}

require_file() {
    # require_file <path> <description>
    if [ -f "$1" ]; then ok "file $1 — $2"; else fail "missing file: $1 ($2)"; fi
}

optional_dir() {
    # optional_dir <path> <description>
    if [ -d "$1" ] && [ -n "$(ls -A "$1" 2>/dev/null || true)" ]; then
        ok "directory $1 — $2"
    elif [ -d "$1" ]; then
        pending "directory $1 exists but is empty — $2"
    else
        pending "directory $1 not created yet — $2"
    fi
}

# -----------------------------------------------------------------------------
printf '%s: repository root %s\n' "$PROG" "$REPO_ROOT"

# -----------------------------------------------------------------------------
section "[1/9] Top-level layout (spec §2.2)"
# -----------------------------------------------------------------------------
require_dir apps    "frontend and device clients"
require_dir services "backend services"
require_dir infra   "Docker, local infrastructure, deployment configuration"
require_dir docs    "architecture, API, data model and operations documentation"
require_dir tests   "integration, end-to-end and load tests"

optional_dir apps/web            "Next.js frontend (spec §2.1)"
optional_dir apps/desktop-bridge "optional local scanner bridge (spec §8.2)"
optional_dir tests/e2e           "end-to-end scenarios (spec §28.3)"
optional_dir tests/load          "load/performance tests (spec §27)"

# -----------------------------------------------------------------------------
section "[2/9] Backend service (spec §2.1)"
# -----------------------------------------------------------------------------
require_dir  services/api                                   "Spring Boot backend"
require_file services/api/pom.xml                            "Maven project definition"
require_file services/api/mvnw                               "./mvnw — the ONLY supported Maven entry point (spec §30)"
require_dir  services/api/.mvn                               "Maven wrapper metadata"
require_file services/api/src/main/resources/application.yml "environment-driven configuration (BC_* variables)"
require_dir  services/api/src/main/resources/db/migration    "Flyway migrations"
require_dir  services/api/src/test                           "backend tests (spec §28.1)"

if [ -x services/api/mvnw ]; then
    ok "services/api/mvnw is executable"
else
    fail "services/api/mvnw is not executable (fix: chmod +x services/api/mvnw)"
fi

# Flyway migration inventory: at least one V*__*.sql, no duplicate version
# numbers (Flyway would refuse to start), no out-of-order gaps that matter.
if [ -d services/api/src/main/resources/db/migration ]; then
    MIGRATION_COUNT="$(find services/api/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*__*.sql' | wc -l | tr -d ' ')"
    if [ "$MIGRATION_COUNT" -ge 1 ]; then
        ok "Flyway migrations found: $MIGRATION_COUNT file(s)"
    else
        fail "no V*__*.sql migration found in services/api/src/main/resources/db/migration"
    fi

    DUPES="$(find services/api/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*__*.sql' -exec basename {} \; \
        | sed -E 's/^V([0-9]+)__.*/\1/' | sort | uniq -d | tr '\n' ' ')"
    if [ -z "${DUPES// /}" ]; then
        ok "no duplicate Flyway version numbers"
    else
        fail "duplicate Flyway version number(s): $DUPES"
    fi

    # Repeatable migrations (R__) are not used in this project; flag them early
    # because they change the migration ordering semantics.
    if find services/api/src/main/resources/db/migration -maxdepth 1 -type f -name 'R__*.sql' | grep -q .; then
        note "repeatable Flyway migrations (R__*) are present — confirm this is intentional"
    fi
fi

# -----------------------------------------------------------------------------
section "[3/9] Frontend application (spec §2.1) — owned by another workstream"
# -----------------------------------------------------------------------------
if [ -f apps/web/package.json ]; then
    ok "apps/web/package.json exists"
    if [ -f apps/web/pnpm-lock.yaml ]; then
        ok "apps/web/pnpm-lock.yaml exists (reproducible installs / CI --frozen-lockfile)"
    elif [ "$ALLOW_WEB_PENDING" -eq 1 ]; then
        pending "apps/web/pnpm-lock.yaml is missing — CI --frozen-lockfile and the web image build require it"
    else
        fail "apps/web/pnpm-lock.yaml is missing — CI and infra/docker/web.Dockerfile require it"
    fi

    # next.config.* must enable the standalone output that web.Dockerfile copies.
    NEXT_CONFIG=""
    for candidate in apps/web/next.config.ts apps/web/next.config.mjs apps/web/next.config.js apps/web/next.config.mts; do
        [ -f "$candidate" ] && NEXT_CONFIG="$candidate" && break
    done
    if [ -z "$NEXT_CONFIG" ]; then
        pending "apps/web/next.config.* not found — cannot verify output: \"standalone\""
    elif grep -qE "output[[:space:]]*:[[:space:]]*['\"]standalone['\"]" "$NEXT_CONFIG"; then
        ok "$NEXT_CONFIG sets output: \"standalone\" (required by infra/docker/web.Dockerfile)"
    elif [ "$ALLOW_WEB_PENDING" -eq 1 ]; then
        pending "$NEXT_CONFIG does not set output: \"standalone\" yet — the web image build and the CI standalone check will fail until it does"
    else
        fail "$NEXT_CONFIG does not set output: \"standalone\" — the web image build will fail (fix: add 'output: \"standalone\"' to apps/web/next.config.ts)"
    fi
else
    pending "apps/web is not implemented yet (package.json missing) — see README.md status table"
fi

# -----------------------------------------------------------------------------
section "[4/9] Infrastructure (spec §30)"
# -----------------------------------------------------------------------------
require_file infra/docker-compose.yml          "development stack (Postgres, MinIO, api, worker, web)"
require_file infra/docker-compose.prod.yml     "production overrides"
require_file infra/docker/api.Dockerfile       "backend image (api + worker)"
require_file infra/docker/web.Dockerfile       "frontend image"
require_file infra/.env.example                "environment template — the only env file that may be committed"
require_dir  infra/scripts                     "operational scripts"
require_file infra/scripts/backup.sh           "pg_dump + object-storage backup"
require_file infra/scripts/restore.sh          "restore into a target database"
require_file infra/scripts/verify-restore.sh   "restore drill with integrity checks"
require_file infra/scripts/wait-for.sh         "TCP readiness gate"
require_file infra/scripts/bootstrap-admin.sh  "first administrator bootstrap"
require_file infra/scripts/validate-compose.sh "static Compose/Dockerfile validation (works without Docker)"
require_file infra/scripts/check-no-env-committed.sh "secret-hygiene guard used by CI"

# -----------------------------------------------------------------------------
section "[5/9] CI/CD (spec §30)"
# -----------------------------------------------------------------------------
require_dir  .github                        "GitHub configuration"
require_file .github/workflows/ci.yml       "build, test, security and compose validation pipeline"

# -----------------------------------------------------------------------------
section "[6/9] Documentation (spec §36)"
# -----------------------------------------------------------------------------
require_file README.md              "product overview, setup, test and status documentation"
require_file docs/task.md           "the specification"
require_file docs/runbook.md        "operations runbook (start/stop, migrations, rollback, backup drill)"
require_file docs/deployment.md     "production deployment guide"
require_file docs/integrations.md   "integration catalogue and owner actions"
optional_dir docs                   "architecture, data model, security, API documentation"

# -----------------------------------------------------------------------------
section "[7/9] Guards: no committed secrets, executable scripts"
# -----------------------------------------------------------------------------
# 7a. No .env file may be tracked by git (spec §21: secrets must never be in the
#     repository). Only .env.example / *.env.example are allowed.
if command -v git >/dev/null 2>&1 && [ -d .git ]; then
    TRACKED_ENV="$(git ls-files 2>/dev/null | grep -E '(^|/)\.env($|\.)' | grep -v -E '\.example$' || true)"
    if [ -n "$TRACKED_ENV" ]; then
        fail "secret-bearing env file(s) are tracked by git: $(printf '%s ' $TRACKED_ENV)"
    else
        ok "no .env file is tracked by git"
    fi
    if [ -f infra/.env ]; then
        PERMS="$(ls -l infra/.env | awk '{print $1}')"
        case "$PERMS" in
            ??????---*) ok "infra/.env exists with restrictive permissions ($PERMS)" ;;
            *)          fail "infra/.env exists but is not private ($PERMS) — run: chmod 600 infra/.env" ;;
        esac
    else
        note "infra/.env does not exist (copy infra/.env.example before running Compose)"
    fi
else
    note "not a git worktree — skipped the tracked-.env guard"
fi

# 7b. Every shell script in the owned trees must be executable and must pass
#     `bash -n`; a script that is not executable is a broken deliverable.
SHELL_SCRIPTS="$(find infra/scripts tests -type f -name '*.sh' 2>/dev/null | sort || true)"
if [ -n "$SHELL_SCRIPTS" ]; then
    BAD_PERMS=""
    BAD_SYNTAX=""
    for script in $SHELL_SCRIPTS; do
        [ -x "$script" ] || BAD_PERMS="${BAD_PERMS} ${script}"
        bash -n "$script" 2>/dev/null || BAD_SYNTAX="${BAD_SYNTAX} ${script}"
    done
    if [ -z "${BAD_PERMS// /}" ]; then
        ok "all $(printf '%s\n' $SHELL_SCRIPTS | wc -l | tr -d ' ') shell script(s) are executable"
    else
        fail "shell script(s) without the executable bit:${BAD_PERMS}"
    fi
    if [ -z "${BAD_SYNTAX// /}" ]; then
        ok "all shell scripts pass 'bash -n'"
    else
        fail "shell script(s) with syntax errors:${BAD_SYNTAX}"
    fi
else
    fail "no shell script found under infra/scripts or tests"
fi

# 7c. YAML files must parse. Two layers:
#     * plain files are checked with yaml.safe_load;
#     * the Compose files are delegated to validate-compose.sh, because
#       docker-compose.prod.yml uses the Compose-specific !reset/!override tags
#       that a plain loader cannot parse. Skipping them would leave the most
#       security-relevant file unchecked, so it is a FAILURE when the delegated
#       check cannot run.
YAML_FILES="$(find infra .github -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null | sort || true)"
if [ -n "$YAML_FILES" ]; then
    if command -v python3 >/dev/null 2>&1; then
        BAD_YAML=""
        PLAIN_COUNT=0
        for yaml_file in $YAML_FILES; do
            case "$yaml_file" in
                infra/docker-compose.yml|infra/docker-compose.prod.yml) continue ;;
            esac
            PLAIN_COUNT=$((PLAIN_COUNT + 1))
            if ! python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1], encoding="utf-8"))' "$yaml_file" >/dev/null 2>&1; then
                BAD_YAML="${BAD_YAML} ${yaml_file}"
            fi
        done
        if [ -z "${BAD_YAML// /}" ]; then
            ok "$PLAIN_COUNT plain YAML file(s) under infra/ and .github/ parse"
        else
            fail "YAML parse error in:${BAD_YAML}"
        fi

        if [ -x infra/scripts/validate-compose.sh ]; then
            if infra/scripts/validate-compose.sh >/tmp/bc-validate-compose.out 2>&1; then
                ok "compose validation passed (infra/scripts/validate-compose.sh)"
                [ "$QUIET" -eq 1 ] || sed 's/^/  /' /tmp/bc-validate-compose.out | tail -n 2
            else
                fail "compose validation FAILED — see the output below"
                sed 's/^/  /' /tmp/bc-validate-compose.out >&2
            fi
        else
            fail "infra/scripts/validate-compose.sh is missing or not executable — the Compose files (with !reset tags) would go unchecked"
        fi
    else
        note "python3/PyYAML not available — YAML parse check SKIPPED (not a pass)"
    fi
else
    fail "no YAML file found under infra/ or .github/"
fi

# -----------------------------------------------------------------------------
section "[8/9] Docker availability"
# -----------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then
        ok "docker daemon reachable — run 'docker compose -f infra/docker-compose.yml config' for full validation"
    else
        note "docker CLI present but the daemon is not reachable — compose validation must run in CI"
    fi
else
    note "docker is NOT installed here: infra/docker-compose*.yml and the Dockerfiles are"
    note "statically validated only (YAML parse + CI 'compose-validation' job). No container"
    note "image or stack has been executed in this environment — see docs/runbook.md."
fi

# -----------------------------------------------------------------------------
section "[9/9] Summary"
# -----------------------------------------------------------------------------
printf '  checks: %s   pending: %s   failures: %s\n' "$CHECKS" "$PENDING" "$FAILURES"

if [ "$FAILURES" -gt 0 ]; then
    printf '%s: FAIL — %s structural problem(s)\n' "$PROG" "$FAILURES" >&2
    exit 1
fi

if [ "$PENDING" -gt 0 ] && [ "$STRICT" -eq 1 ]; then
    printf '%s: FAIL (--strict) — %s path(s) still PENDING\n' "$PROG" "$PENDING" >&2
    exit 1
fi

if [ "$PENDING" -gt 0 ]; then
    printf '%s: PASS with %s pending item(s) (use --strict to make them fatal)\n' "$PROG" "$PENDING"
else
    printf '%s: PASS — repository layout matches spec §2.2\n' "$PROG"
fi
exit 0
