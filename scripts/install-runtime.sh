#!/usr/bin/env bash
# =============================================================================
#  install-runtime.sh — install Firmspace Robots Runtime on this host
# =============================================================================
#  Requirements: Docker Engine 24+ with Compose v2, curl, git (for the source
#  checkout), 2 vCPU / 4 GB RAM / 10 GB free disk as a minimum.
#
#  The installer:
#    * asks for the public URL, bind address and data directory (or accepts them
#      as flags for unattended use);
#    * generates the database password, the AES key, the runtime admin token and
#      the installation's instance id — there is no default password anywhere;
#    * writes infra/runtime.env (mode 600) and the instance-id file;
#    * builds the API image from this checkout and starts the stack;
#    * calls bootstrap-runtime.sh, which creates tenant/workspace/owner, issues
#      the first token and prints it once.
#
#  Examples:
#      scripts/install-runtime.sh
#      scripts/install-runtime.sh --lan --tenant "Muster GmbH" \
#          --email ops@example.de --license-key-file ./license.token \
#          --license-secret-file ./license.secret
#      scripts/install-runtime.sh --public-url https://runtime.example.de \
#          --bind-address 127.0.0.1 --non-interactive ...
#
#  Re-running against an existing installation is refused: rotating the
#  generated database password would lock the API out of its own data. To
#  re-provision credentials use --force on a fresh installation only; to issue
#  another token use scripts/bootstrap-runtime.sh.
#
#  Exit codes: 0 success, 1 configuration/runtime error, 2 usage error,
#              3 health timeout from the bootstrap step, 4 bootstrap refused.
# =============================================================================
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROG="$(basename "$0")"

COMPOSE_FILE="${REPO_ROOT}/infra/docker-compose.runtime.yml"
ENV_FILE="${REPO_ROOT}/infra/runtime.env"
BOOTSTRAP="${SCRIPT_DIR}/bootstrap-runtime.sh"

BIND_ADDRESS="127.0.0.1"
API_PORT="${BC_API_PORT:-8080}"
PUBLIC_URL=""
DATA_DIR="${REPO_ROOT}/infra/runtime-data"
TENANT="${BC_RUNTIME_TENANT:-Firmspace Robots Runtime}"
EMAIL="${BC_RUNTIME_OWNER_EMAIL:-}"
OWNER_NAME="${BC_RUNTIME_OWNER_NAME:-Runtime Owner}"
LICENSE_KEY="${LICENSE_KEY:-}"
LICENSE_SECRET="${LICENSE_SECRET:-}"
LICENSE_KEY_FILE=""
LICENSE_SECRET_FILE=""
ADMIN_TOKEN_FILE=""
INSTALL_DIR=""
NON_INTERACTIVE=0
FORCE=0
NO_BUILD=0
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-firmspace-robots-runtime}"

detect_lan_ip() {
    if [ "$(uname -s)" = "Darwin" ]; then
        ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true
    else
        hostname -I 2>/dev/null | awk '{print $1}' || true
    fi
}

default_public_url() {
    if [ "$BIND_ADDRESS" = "0.0.0.0" ]; then
        local ip
        ip="$(detect_lan_ip)"
        if [ -n "$ip" ]; then
            printf 'http://%s:%s' "$ip" "$API_PORT"
            return
        fi
    fi
    printf 'http://localhost:%s' "$API_PORT"
}

log()  { printf '%s: %s\n' "$PROG" "$*"; }
warn() { printf '%s: WARNING: %s\n' "$PROG" "$*" >&2; }
die()  { printf '%s: ERROR: %s\n' "$PROG" "$*" >&2; exit 1; }
usage() { sed -n '2,38p' "$0" | sed -n 's/^# \{0,1\}//p'; }

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)          usage; exit 0 ;;
        --lan)              BIND_ADDRESS="0.0.0.0"; shift ;;
        --localhost)        BIND_ADDRESS="127.0.0.1"; shift ;;
        --bind-address)     [ "$#" -ge 2 ] || die "--bind-address needs a value"; BIND_ADDRESS="$2"; shift 2 ;;
        --api-port|--port)  [ "$#" -ge 2 ] || die "$1 needs a value"; API_PORT="$2"; shift 2 ;;
        --dir)              [ "$#" -ge 2 ] || die "--dir needs a value"; INSTALL_DIR="$2"; shift 2 ;;
        --public-url)       [ "$#" -ge 2 ] || die "--public-url needs a value"; PUBLIC_URL="$2"; shift 2 ;;
        --domain)           [ "$#" -ge 2 ] || die "--domain needs a value"; PUBLIC_URL="https://$2"; shift 2 ;;
        --data-dir)         [ "$#" -ge 2 ] || die "--data-dir needs a value"; DATA_DIR="$2"; shift 2 ;;
        --tenant)           [ "$#" -ge 2 ] || die "--tenant needs a value"; TENANT="$2"; shift 2 ;;
        --email)            [ "$#" -ge 2 ] || die "--email needs a value"; EMAIL="$2"; shift 2 ;;
        --owner-name)       [ "$#" -ge 2 ] || die "--owner-name needs a value"; OWNER_NAME="$2"; shift 2 ;;
        --license-key-file) [ "$#" -ge 2 ] || die "--license-key-file needs a value"; LICENSE_KEY_FILE="$2"; shift 2 ;;
        --license-secret-file) [ "$#" -ge 2 ] || die "--license-secret-file needs a value"; LICENSE_SECRET_FILE="$2"; shift 2 ;;
        --admin-token-file) [ "$#" -ge 2 ] || die "--admin-token-file needs a value"; ADMIN_TOKEN_FILE="$2"; shift 2 ;;
        --env-file)         [ "$#" -ge 2 ] || die "--env-file needs a value"; ENV_FILE="$2"; shift 2 ;;
        --project-name)     [ "$#" -ge 2 ] || die "--project-name needs a value"; COMPOSE_PROJECT_NAME="$2"; shift 2 ;;
        --non-interactive)  NON_INTERACTIVE=1; shift ;;
        --force)            FORCE=1; shift ;;
        --no-build)         NO_BUILD=1; shift ;;
        *)                  printf '%s: unknown option: %s\n' "$PROG" "$1" >&2; usage >&2; exit 2 ;;
    esac
done

command -v docker >/dev/null 2>&1 || die "docker is not installed"
docker compose version >/dev/null 2>&1 || die "docker compose (v2) is not available"
docker info >/dev/null 2>&1 || die "the docker daemon is not reachable (start Docker / check your user's docker group)"
command -v curl >/dev/null 2>&1 || die "curl is required"
[ -r "$COMPOSE_FILE" ] || die "missing ${COMPOSE_FILE}"
[ -x "$BOOTSTRAP" ] || die "missing or non-executable ${BOOTSTRAP}"

# --dir is the documented one-flag installation root: secrets in <dir>/runtime.env,
# blobs and the instance id in <dir>/runtime-data. It is resolved against the
# current directory so a relative value cannot later be interpreted relative to
# infra/ by Compose.
if [ -n "$INSTALL_DIR" ]; then
    case "$INSTALL_DIR" in
        /*) ;;
        *) INSTALL_DIR="$(pwd)/${INSTALL_DIR}" ;;
    esac
    ENV_FILE="${INSTALL_DIR}/runtime.env"
    DATA_DIR="${INSTALL_DIR}/runtime-data"
fi

if [ -e "$ENV_FILE" ] && [ "$FORCE" -ne 1 ]; then
    cat >&2 <<EOF
${PROG}: ERROR: ${ENV_FILE} already exists.

Refusing to overwrite it: that file holds the only copy of the database password,
the AES key and the runtime admin token. A new database password would lock the
API out of its existing volume, and a new AES key would make encrypted
credentials unreadable.

  * to run an existing installation: scripts/bootstrap-runtime.sh
  * to start over on a fresh installation: --force (this deletes nothing, but
    the database volume must be removed deliberately; see docs/deployment/RUNTIME.md)
EOF
    exit 1
fi

if [ "$NON_INTERACTIVE" -eq 0 ] && [ -t 0 ]; then
    printf '\nFirmspace Robots Runtime installer\n'
    printf '==================================\n'
    if [ -z "$PUBLIC_URL" ]; then
        DEFAULT_PUBLIC="$(default_public_url)"
        read -r -p "Public API URL [${DEFAULT_PUBLIC}]: " answer || true
        PUBLIC_URL="${answer:-$DEFAULT_PUBLIC}"
    fi
    read -r -p "Bind API to [${BIND_ADDRESS}] (127.0.0.1 = local only, 0.0.0.0 = LAN): " answer || true
    BIND_ADDRESS="${answer:-$BIND_ADDRESS}"
    read -r -p "Data directory [${DATA_DIR}]: " answer || true
    DATA_DIR="${answer:-$DATA_DIR}"
    read -r -p "Tenant name [${TENANT}]: " answer || true
    TENANT="${answer:-$TENANT}"
    while [ -z "$EMAIL" ]; do
        read -r -p "Owner e-mail (for audit/token ownership): " EMAIL || true
    done
    read -r -p "Owner display name [${OWNER_NAME}]: " answer || true
    OWNER_NAME="${answer:-$OWNER_NAME}"
    if [ -z "$LICENSE_KEY" ] && [ -z "$LICENSE_KEY_FILE" ]; then
        read -r -p "Licence key (empty = start in LICENSE_MISSING; agents answer 402): " LICENSE_KEY || true
    fi
    if [ -n "$LICENSE_KEY$LICENSE_KEY_FILE" ] && [ -z "$LICENSE_SECRET" ] && [ -z "$LICENSE_SECRET_FILE" ]; then
        read -r -s -p "Licence secret (the shared HMAC secret, min 32 chars): " LICENSE_SECRET || true
        printf '\n'
    fi
else
    if [ -z "$PUBLIC_URL" ]; then
        PUBLIC_URL="$(default_public_url)"
    fi
    if [ -z "$EMAIL" ]; then
        die "no owner e-mail in non-interactive mode; pass --email"
    fi
fi

[ -n "$TENANT" ] || die "the tenant name must not be empty"
[ -n "$EMAIL" ] || die "the owner e-mail must not be empty"
case "$DATA_DIR" in
    *[[:space:]]*) die "the data directory must not contain whitespace (env-file and shell sourcing)" ;;
esac
case "$PUBLIC_URL" in
    *[[:space:]]*) die "the public URL must not contain whitespace" ;;
esac
case "$API_PORT" in ''|*[!0-9]*) die "--api-port must be a number" ;; esac
[ "$API_PORT" -ge 1 ] && [ "$API_PORT" -le 65535 ] || die "--api-port must be between 1 and 65535"

if [ -n "$LICENSE_KEY_FILE" ]; then
    [ -r "$LICENSE_KEY_FILE" ] || die "cannot read licence key file: ${LICENSE_KEY_FILE}"
    LICENSE_KEY="$(tr -d '\r\n' < "$LICENSE_KEY_FILE")"
fi
if [ -n "$LICENSE_SECRET_FILE" ]; then
    [ -r "$LICENSE_SECRET_FILE" ] || die "cannot read licence secret file: ${LICENSE_SECRET_FILE}"
    LICENSE_SECRET="$(tr -d '\r\n' < "$LICENSE_SECRET_FILE")"
fi
if [ -n "$LICENSE_KEY" ] && [ -z "$LICENSE_SECRET" ]; then
    die "a licence key was given without LICENSE_SECRET; the runtime cannot verify the key"
fi
if [ -z "$LICENSE_KEY" ]; then
    warn "no licence key: the installation will start in LICENSE_MISSING and agent endpoints answer 402"
fi
if [ -n "$LICENSE_SECRET" ] && [ "${#LICENSE_SECRET}" -lt 32 ]; then
    die "LICENSE_SECRET must be at least 32 characters"
fi
for value in "$LICENSE_KEY" "$LICENSE_SECRET"; do
    case "$value" in
        '') continue ;;
        *[!A-Za-z0-9._~+/=-]*) die "licence key/secret may only contain [A-Za-z0-9._~+/=-]" ;;
    esac
done

# Resolve the data directory to an absolute path. The compose bind mount is
# resolved relative to infra/, so a relative value would surprise the operator.
case "$DATA_DIR" in
    /*) ;;
    *) DATA_DIR="${REPO_ROOT}/${DATA_DIR}" ;;
esac
mkdir -p "$DATA_DIR"
chmod 700 "$DATA_DIR" 2>/dev/null || true

INSTANCE_FILE="${DATA_DIR}/instance-id"
if [ -s "$INSTANCE_FILE" ]; then
    INSTANCE_ID="$(tr -d '[:space:]' < "$INSTANCE_FILE")"
else
    if command -v uuidgen >/dev/null 2>&1; then
        INSTANCE_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
    elif [ -r /proc/sys/kernel/random/uuid ]; then
        INSTANCE_ID="$(tr '[:upper:]' '[:lower:]' < /proc/sys/kernel/random/uuid)"
    else
        INSTANCE_ID="$(openssl rand -hex 16 2>/dev/null || true)"
    fi
    [ -n "$INSTANCE_ID" ] || die "could not generate an instance id"
    printf '%s\n' "$INSTANCE_ID" > "$INSTANCE_FILE"
fi
chmod 600 "$INSTANCE_FILE" 2>/dev/null || true
[ -n "$INSTANCE_ID" ] || die "the instance id file is empty"

random_hex() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex "$1"
    else
        # /dev/urandom is available on every platform this product supports; the
        # fallback exists so that a minimal host without OpenSSL still installs.
        dd if=/dev/urandom bs=1 count="$1" 2>/dev/null | od -An -tx1 | tr -d ' \n'
    fi
}
random_base64_32() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32 | tr -d '\n'
    else
        dd if=/dev/urandom bs=1 count=32 2>/dev/null | base64 | tr -d '\n'
    fi
}

DB_PASSWORD="${BC_DB_PASSWORD:-$(random_hex 24)}"
ENCRYPTION_KEY="${BC_ENCRYPTION_KEY:-$(random_base64_32)}"
if [ -n "$ADMIN_TOKEN_FILE" ]; then
    [ -r "$ADMIN_TOKEN_FILE" ] || die "cannot read admin token file: ${ADMIN_TOKEN_FILE}"
    ADMIN_TOKEN="$(tr -d '\r\n' < "$ADMIN_TOKEN_FILE")"
    [ -n "$ADMIN_TOKEN" ] || die "the admin token file is empty: ${ADMIN_TOKEN_FILE}"
else
    ADMIN_TOKEN="${BC_RUNTIME_ADMIN_TOKEN:-$(random_hex 32)}"
fi
DB_NAME="${BC_DB_NAME:-firmspace}"
DB_USER="${BC_DB_USER:-firmspace}"
TZ_VALUE="${TZ:-Europe/Berlin}"

[ "${#ADMIN_TOKEN}" -ge 32 ] || die "the runtime admin token must be at least 32 characters"
case "$ADMIN_TOKEN" in
    *[!A-Za-z0-9._~+/=-]*) die "the runtime admin token may only contain [A-Za-z0-9._~+/=-]" ;;
esac
for value in "$DB_PASSWORD" "$ENCRYPTION_KEY" "$ADMIN_TOKEN"; do
    case "$value" in
        *[[:space:]]*) die "generated secret contains whitespace; refusing to write the env file" ;;
    esac
done

ENV_DIR="$(dirname "$ENV_FILE")"
mkdir -p "$ENV_DIR"
TMP_ENV="${ENV_FILE}.tmp.$$"
cleanup_tmp() { rm -f "${TMP_ENV:-}"; }
trap cleanup_tmp EXIT

cat > "$TMP_ENV" <<EOF
# =============================================================================
#  Firmspace Robots Runtime — generated by scripts/install-runtime.sh
#  DO NOT COMMIT. Contains the database password, the AES key and the admin
#  token. Mode 600. Rotate by editing this file and recreating the api service.
# =============================================================================
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}
TZ=${TZ_VALUE}

# --- database ---
BC_DB_NAME=${DB_NAME}
BC_DB_USER=${DB_USER}
BC_DB_PASSWORD=${DB_PASSWORD}

# --- API ---
BC_BIND_ADDRESS=${BIND_ADDRESS}
BC_API_PORT=${API_PORT}
BC_PUBLIC_BASE_URL=${PUBLIC_URL}
BC_ENCRYPTION_KEY=${ENCRYPTION_KEY}
BC_RUNTIME_ADMIN_TOKEN=${ADMIN_TOKEN}
BC_DATA_DIR=${DATA_DIR}
BC_STORAGE_TYPE=local-fs
BC_OCR_ENABLED=true
BC_ALLOW_NO_OCR=false
BC_REGISTRATION_OPEN=false
BC_FLYWAY_ENABLED=true
BC_MALWARE_SCANNER=none
BC_ALLOW_UNSCANNED=true
BC_LOG_LEVEL=INFO
BC_IMAGE_TAG=local
BC_BUILD_REVISION=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)

# --- licence (optional; empty LICENSE_KEY = LICENSE_MISSING) ---
LICENSE_KEY=${LICENSE_KEY}
LICENSE_SECRET=${LICENSE_SECRET}
BC_LICENSE_ENFORCEMENT=true
LICENSE_GRACE_DAYS=14
EOF
chmod 600 "$TMP_ENV"
mv "$TMP_ENV" "$ENV_FILE"
trap - EXIT

log "wrote ${ENV_FILE} (mode 600)"
log "data directory: ${DATA_DIR}"
log "instance id: ${INSTANCE_ID}"

if [ "$(id -u)" -eq 0 ]; then
    # The API image runs as UID 10001. A bind mount created by root is not
    # writable by that user otherwise.
    chown -R 10001:10001 "$DATA_DIR" 2>/dev/null || true
else
    warn "if the API cannot write to /app/data, run: sudo chown -R 10001:10001 '${DATA_DIR}'"
fi

if [ "$NO_BUILD" -eq 0 ]; then
    log "building the API image (this can take several minutes on the first run)"
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" build
else
    log "--no-build: using the image configured in the compose file"
fi

log "starting the stack"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d

# The bootstrap script also waits for health; --skip-compose keeps it from
# running `up` a second time.
"$BOOTSTRAP" --env-file "$ENV_FILE" --compose-file "$COMPOSE_FILE" \
    --tenant "$TENANT" --email "$EMAIL" --name "$OWNER_NAME" --skip-compose

cat <<EOF

Installation files
==================
  environment:  ${ENV_FILE}   (mode 600; back it up separately)
  data:         ${DATA_DIR}
  compose:      ${COMPOSE_FILE}
  docs:         ${REPO_ROOT}/docs/deployment/RUNTIME.md

Next steps
==========
  1. Put a TLS reverse proxy in front of the API if it is reachable from a
     network. The stack itself serves plain HTTP.
  2. Back up PostgreSQL and the data directory; the exact commands are in the
     documentation.
  3. Check health and licence:
       curl -fsS http://127.0.0.1:${API_PORT}/actuator/health/readiness
       curl -fsS http://127.0.0.1:${API_PORT}/api/v1/license
  4. Revoke or issue tokens through /api/v1/runtime/tokens with the admin
     secret from ${ENV_FILE}.
EOF
