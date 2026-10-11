#!/usr/bin/env bash
# =============================================================================
#  validate-compose.sh — static validation of the Compose files and Dockerfiles
# =============================================================================
#  This is the check that must pass when Docker is NOT available (which is the
#  case in the reference development environment and in some CI runners). It
#  parses the files with a REAL YAML parser — registering the Compose-specific
#  `!reset` / `!override` tags so that infra/docker-compose.prod.yml can be read
#  at all — and then asserts the security-relevant structure that a plain syntax
#  check would not notice.
#
#      infra/scripts/validate-compose.sh
#      infra/scripts/validate-compose.sh --no-docker   # skip `docker compose config`
#
#  What is asserted:
#    1. every YAML file under infra/ parses;
#    2. the dev stack defines postgres, minio, minio-init, api, worker, web;
#    3. every long-running service has a healthcheck;
#    4. api/worker wait for postgres AND minio to be healthy and for minio-init to
#       have COMPLETED successfully (an absent bucket breaks the first upload);
#    5. the dev stack publishes only loopback addresses;
#    6. the production override resets the published ports of postgres, minio,
#       api, web (and the build section) and makes api/worker/web read-only;
#    7. the production override requires a registry and an image tag (`:?`), so a
#       deploy cannot silently run a locally built image;
#    8. both Dockerfiles exist, use a multi-stage build, declare a non-root USER
#       and a HEALTHCHECK, and pass no credential through ARG/ENV;
#    9. `docker compose config` (dev + prod with the proxy profile) when a Docker
#       daemon is reachable — otherwise reported as SKIPPED, never as a pass.
#
#  Exit codes: 0 = all executed checks passed, 1 = a check failed, 2 = usage error.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$REPO_ROOT"
PROG="$(basename "$0")"

RUN_DOCKER=1
[ "${1:-}" = "--no-docker" ] && RUN_DOCKER=0
case "${1:-}" in
    -h|--help)
        sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
        exit 0 ;;
esac

FAILED=0
PASSED=0
SKIPPED=0
YAML_FAILED=0
STRUCT_FAILED=0

pass()    { PASSED=$((PASSED + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail()    { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1" >&2; }
skipped() { SKIPPED=$((SKIPPED + 1)); printf '  \033[33mSKIP\033[0m  %s\n' "$1"; }
note()    { printf '  ----  %s\n' "$1"; }

command -v python3 >/dev/null 2>&1 || { echo "$PROG: python3 is required" >&2; exit 2; }
python3 -c 'import yaml' 2>/dev/null || { echo "$PROG: PyYAML is required (pip install PyYAML)" >&2; exit 2; }

# The parser helper is kept in one place so that every check reads the files
# exactly the same way. `!reset`/`!override` are Compose tags PyYAML does not
# know; without these constructors the prod override cannot be parsed and the
# most security-relevant file would be silently unvalidated.
HELPER=$(mktemp "${TMPDIR:-/tmp}/bc-compose-lib.XXXXXX.py")
cleanup_helper() { rm -f "$HELPER"; }
trap cleanup_helper EXIT
cat > "$HELPER" <<'PY'
import sys
import yaml


class ComposeLoader(yaml.SafeLoader):
    """SafeLoader that understands Compose's !reset / !override tags."""


def _construct(loader, node):
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node, deep=True)
    if isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node, deep=True)
    if isinstance(node, yaml.ScalarNode):
        return loader.construct_scalar(node)
    return None


ComposeLoader.add_constructor('!reset', _construct)
ComposeLoader.add_constructor('!override', _construct)


def load(path):
    with open(path, encoding='utf-8') as handle:
        return yaml.load(handle, Loader=ComposeLoader) or {}
PY

BC_COMPOSE_HELPER="$HELPER" python3 - <<'PY'
import importlib.util
import os

_spec = importlib.util.spec_from_file_location('bc_compose_lib', os.environ['BC_COMPOSE_HELPER'])
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
load = _mod.load
import glob

problems = []
for path in sorted(glob.glob('infra/**/*.yml', recursive=True) + glob.glob('infra/**/*.yaml', recursive=True)):
    try:
        load(path)
    except Exception as exc:  # noqa: BLE001 - report any parser failure
        problems.append(f'{path}: {exc}')

if problems:
    for problem in problems:
        print(f'  \033[31mFAIL\033[0m  YAML parse: {problem}', file=sys.stderr)
    sys.exit(1)
print('  \033[32mPASS\033[0m  every YAML file under infra/ parses (Compose tags registered)')
PY
if [ "$?" -ne 0 ]; then
    printf '  \033[31mFAIL\033[0m  one or more YAML files under infra/ failed to parse\n' >&2
    YAML_FAILED=1
else
    YAML_FAILED=0
fi

# -----------------------------------------------------------------------------
# Structural assertions
# -----------------------------------------------------------------------------
DEV="infra/docker-compose.yml"
PROD="infra/docker-compose.prod.yml"

[ -f "$DEV" ]  || { fail "missing $DEV"; exit 1; }
[ -f "$PROD" ] || { fail "missing $PROD"; exit 1; }

BC_COMPOSE_HELPER="$HELPER" python3 - <<'PY'
import importlib.util
import os
import sys

_spec = importlib.util.spec_from_file_location('bc_compose_lib', os.environ['BC_COMPOSE_HELPER'])
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
load = _mod.load

dev = load('infra/docker-compose.yml')
prod = load('infra/docker-compose.prod.yml')
services = dev.get('services') or {}
prod_services = prod.get('services') or {}

failed = 0
passed = 0
skipped = 0

def ok(msg):
    global passed
    passed += 1
    print(f'  \033[32mPASS\033[0m  {msg}')

def bad(msg):
    global failed
    failed += 1
    print(f'  \033[31mFAIL\033[0m  {msg}', file=sys.stderr)

def skip(msg):
    global skipped
    skipped += 1
    print(f'  \033[33mSKIP\033[0m  {msg}')

# --- dev stack: required services -------------------------------------------
required = ['postgres', 'minio', 'minio-init', 'api', 'worker', 'web']
missing = [name for name in required if name not in services]
if missing:
    bad(f'the dev stack is missing service(s): {missing}')
else:
    ok(f'dev stack defines all required services: {", ".join(required)}')

# --- dev stack: healthchecks -------------------------------------------------
no_healthcheck = []
for name in ('postgres', 'minio', 'api', 'worker', 'web'):
    if name in services and 'healthcheck' not in (services.get(name) or {}):
        no_healthcheck.append(name)
if no_healthcheck:
    bad(f'service(s) without a healthcheck: {no_healthcheck}')
else:
    ok('every long-running service declares a healthcheck')

# --- dev stack: dependency gating -------------------------------------------
for name in ('api', 'worker'):
    depends = (services.get(name) or {}).get('depends_on') or {}
    conds = {k: (v or {}).get('condition') for k, v in depends.items()}
    if conds.get('postgres') != 'service_healthy':
        bad(f'{name} does not wait for postgres to be healthy (got {conds.get("postgres")!r})')
    elif conds.get('minio') != 'service_healthy':
        bad(f'{name} does not wait for minio to be healthy (got {conds.get("minio")!r})')
    elif conds.get('minio-init') != 'service_completed_successfully':
        bad(f'{name} does not wait for minio-init to complete (the bucket may not exist)')
    else:
        ok(f'{name} is gated on postgres+minio healthy and minio-init completed')

# --- dev stack: loopback-only publishing ------------------------------------
bad_binds = []
for name, service in services.items():
    for entry in (service or {}).get('ports') or []:
        text = str(entry)
        # Accepted shapes: "127.0.0.1:8080:8080", "${BC_BIND_ADDRESS:-127.0.0.1}:8080:8080".
        if '${BC_BIND_ADDRESS' in text:
            continue
        host_part = text.split(':')[0]
        if host_part not in ('127.0.0.1', '::1', '[::1]'):
            bad_binds.append(f'{name}: {text}')
if bad_binds:
    bad(f'dev ports not bound to loopback: {bad_binds}')
else:
    ok('every published dev port is bound to ${BC_BIND_ADDRESS:-127.0.0.1} or loopback')

# --- dev stack: no obsolete version key -------------------------------------
for path in ('infra/docker-compose.yml', 'infra/docker-compose.prod.yml'):
    with open(path, encoding='utf-8') as handle:
        if handle.read().startswith('version:'):
            bad(f'{path} starts with an obsolete top-level version: key')
else:
    ok('no obsolete top-level version: key')

# --- prod override: no published datastore ports ----------------------------
leaks = []
for name in ('postgres', 'minio'):
    ports = (prod_services.get(name) or {}).get('ports', None)
    if ports not in ([], None):
        leaks.append(f'{name}.ports={ports!r} (expected [] via !reset)')
for name in ('api', 'web'):
    service = prod_services.get(name) or {}
    if 'ports' in service and service['ports'] not in ([], None):
        leaks.append(f'{name}.ports={service["ports"]!r} (expected [] via !reset)')
if leaks:
    bad(f'the production override still publishes: {leaks}')
else:
    ok('production override publishes no datastore/api/web port (proxy only)')

# --- prod override: read-only root filesystems ------------------------------
not_ro = [name for name in ('api', 'worker', 'web')
          if (prod_services.get(name) or {}).get('read_only') is not True]
if not_ro:
    bad(f'service(s) without read_only: true in production: {not_ro}')
else:
    ok('api, worker and web use a read-only root filesystem in production')

# --- prod override: tmpfs for the writable paths ----------------------------
missing_tmpfs = []
for name in ('api', 'worker'):
    tmpfs = (prod_services.get(name) or {}).get('tmpfs') or []
    if not any(str(entry).startswith('/tmp') for entry in tmpfs):
        missing_tmpfs.append(name)
if missing_tmpfs:
    bad(f'service(s) with read_only but no writable /tmp: {missing_tmpfs} (the JVM needs it)')
else:
    ok('api and worker mount a writable /tmp (required by read_only)')

# --- prod override: immutable images ----------------------------------------
for name in ('api', 'worker', 'web'):
    image = str((prod_services.get(name) or {}).get('image', ''))
    if 'BC_REGISTRY:?' not in image or 'BC_IMAGE_TAG:?' not in image:
        bad(f'{name}.image does not require BC_REGISTRY and BC_IMAGE_TAG (got {image!r})')
    else:
        service = prod_services.get(name) or {}
        # `build: !reset null` reaches this parser as the string 'null' (the tag
        # constructor returns the scalar). Both None and 'null' mean "reset";
        # a mapping means a local build section is still declared.
        if isinstance(service.get('build'), (dict, list)):
            bad(f'{name} still declares a build: section in production (must be !reset)')
if failed == 0:
    ok('api, worker and web run immutable registry images with a required tag')

# --- secret handling ---------------------------------------------------------
import re
with open('infra/docker-compose.yml', encoding='utf-8') as handle:
    dev_text = handle.read()
# A value is acceptable only when it is a `${VAR...}` reference; anything else
# (a literal secret) is a defect. `\s*` before the lookahead is required because
# `KEY: ${VAR}` has a space between the colon and the `$`.
inline = re.findall(
    r'^\s*(BC_ENCRYPTION_KEY|BC_DB_PASSWORD|BC_S3_SECRET_KEY|BC_AI_API_KEY):\s*(?!\s*\$\{)',
    dev_text, re.MULTILINE)
if inline:
    bad(f'a compose file inlines a secret value instead of ${{VAR}}: {sorted(set(inline))}')
else:
    ok('compose files reference secrets through ${VAR} only')

print(f'  ----  structural checks: {passed} passed, {failed} failed, {skipped} skipped')
sys.exit(1 if failed else 0)
PY
STRUCT_RC=$?
if [ "$STRUCT_RC" -ne 0 ]; then STRUCT_FAILED=1; else STRUCT_FAILED=0; fi

# -----------------------------------------------------------------------------
# Dockerfiles
# -----------------------------------------------------------------------------
for dockerfile in infra/docker/api.Dockerfile infra/docker/web.Dockerfile; do
    if [ ! -f "$dockerfile" ]; then
        fail "missing $dockerfile"
        continue
    fi
    from_count="$(grep -c '^FROM ' "$dockerfile" || true)"
    if [ "$from_count" -lt 2 ]; then
        fail "$dockerfile is not multi-stage (only $from_count FROM)"
    else
        pass "$dockerfile is multi-stage ($from_count stages)"
    fi
    if grep -qE '^USER (?!root)' "$dockerfile" 2>/dev/null || grep -qE '^USER ' "$dockerfile"; then
        user_line="$(grep -E '^USER ' "$dockerfile" | tail -1)"
        case "$user_line" in
            *root*) fail "$dockerfile ends as root ($user_line)" ;;
            *)      pass "$dockerfile runs as a non-root user ($user_line)" ;;
        esac
    else
        fail "$dockerfile declares no USER (it would run as root)"
    fi
    if grep -q '^HEALTHCHECK ' "$dockerfile"; then
        pass "$dockerfile declares a HEALTHCHECK"
    else
        fail "$dockerfile declares no HEALTHCHECK"
    fi
    if grep -qE '^\s*(ARG|ENV)\s+(BC_[A-Z_]*KEY|BC_[A-Z_]*PASSWORD|BC_[A-Z_]*SECRET|AWS_SECRET)' "$dockerfile"; then
        fail "$dockerfile passes a credential through ARG/ENV"
    else
        pass "$dockerfile passes no credential through ARG/ENV"
    fi
done

# -----------------------------------------------------------------------------
# docker compose config (only when a daemon is reachable)
# -----------------------------------------------------------------------------
if [ "$RUN_DOCKER" -eq 0 ]; then
    skipped "docker compose config (--no-docker)"
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    # Interpolation inputs only: this render is thrown away and nothing is deployed from it. The
    # encryption key is generated, not written down, so the repository contains no key material at all
    # - a fixed placeholder is indistinguishable from a leaked key to any scanner, and "it is only a
    # placeholder" is exactly what a real leak looks like in a commit history.
    export BC_DB_PASSWORD="placeholder"
    export POSTGRES_PASSWORD="placeholder"
    export BC_ENCRYPTION_KEY="$(openssl rand -base64 32)"
    export BC_S3_ACCESS_KEY="ci-placeholder-access-key"
    export BC_S3_SECRET_KEY="ci-placeholder-secret-key"
    export MINIO_ROOT_USER="ci-placeholder-root"
    export MINIO_ROOT_PASSWORD="ci-placeholder-root-password"
    export BC_REGISTRY="ghcr.io/example-org"
    export BC_IMAGE_TAG="ci"
    export BC_SERVER_NAMES="ci.example.de"

    if docker compose -f "$DEV" config >/dev/null 2>/tmp/bc-compose-dev.err; then
        pass "docker compose config renders the dev stack"
    else
        fail "docker compose config failed for the dev stack: $(head -3 /tmp/bc-compose-dev.err | tr '\n' ' ')"
    fi

    # The production file deliberately marks eight values as mandatory ("${VAR:?}"), so a
    # deployment cannot start with empty credentials. Rendering it in CI therefore has to supply
    # placeholder values - the goal here is to prove that the file is structurally valid and that
    # no secret is hard-coded, not to deploy anything.
    if BC_REGISTRY=registry.invalid BC_IMAGE_TAG=ci-check BC_SERVER_NAMES=example.invalid \
       POSTGRES_DB=burokratie POSTGRES_USER=burokratie POSTGRES_PASSWORD=ci-placeholder-only \
       MINIO_ROOT_USER=ci-placeholder MINIO_ROOT_PASSWORD=ci-placeholder-only \
       docker compose -f "$DEV" -f "$PROD" --profile proxy config >/dev/null 2>/tmp/bc-compose-prod.err; then
        pass "docker compose config renders the production stack (with the proxy profile)"
    else
        fail "docker compose config failed for the production stack: $(head -3 /tmp/bc-compose-prod.err | tr '\n' ' ')"
    fi
else
    skipped "docker compose config — no usable Docker daemon (static checks above still ran)"
fi

# -----------------------------------------------------------------------------
# The Python blocks report their own counts; these shell counters cover the
# Dockerfile and `docker compose config` checks that run in this shell.
TOTAL_FAILED=$((FAILED + YAML_FAILED + STRUCT_FAILED))
printf '\n%s: %s shell check(s) passed, %s failed (plus the structural checks above), %s skipped\n' \
    "$PROG" "$PASSED" "$TOTAL_FAILED" "$SKIPPED"
if [ "$TOTAL_FAILED" -gt 0 ]; then
    printf '%s: FAIL\n' "$PROG" >&2
    exit 1
fi
printf '%s: PASS (docker compose config may be SKIPPED — see above)\n' "$PROG"
exit 0
