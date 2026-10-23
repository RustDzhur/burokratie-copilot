#!/usr/bin/env bash
# =============================================================================
#  bootstrap-admin.sh — create the first administrator
# =============================================================================
#  HOW IT WORKS TODAY (verified against the source, 2026-10-11)
#
#  The backend implements SELF-REGISTRATION, and that registration creates the
#  tenant, its default workspace and the OWNER membership in one transaction:
#
#      POST /api/v1/auth/register     (public endpoint, no authentication)
#      services/api/.../identity/web/AuthController.java
#      services/api/.../identity/auth/AuthService.java  → "creates the tenant,
#      its default workspace and the OWNER membership in one step"
#
#  This script drives exactly that endpoint. Two consequences must be understood
#  before using it:
#
#    1. Self-registration is NOT the same as a controlled admin bootstrap. It is
#       available to anyone who can reach the API, it is rate-limited only by the
#       application's own protections, and it does not verify that the caller is
#       the system owner. Until the planned bootstrap-admin flow exists
#       (docs/implementation-plan.md A-018/A-024: an idempotent token-protected
#       bootstrap, status TODO), an operator must
#       either close registration at the edge (see docs/deployment.md) or accept
#       that the first-come-first-served registration owns the account.
#    2. E-mail verification is required afterwards. Registration returns
#       `nextStep: VERIFY_EMAIL` (or `VERIFY_EMAIL_PENDING_MAIL_CONFIGURATION`
#       when no SMTP sender is configured). With no mail configured, the
#       verification token must be obtained from the operator's own database or
#       logs; this script prints the exact follow-up step and never pretends the
#       account is ready when verification is still pending.
#
#  What this script will NOT do: insert rows into the database directly. Account
#  creation must go through the application so that Argon2id password hashing,
#  audit events and tenant/membership rules are applied (spec §1.2 — no fake
#  functionality, no bypass of security controls).
#
#  Usage:
#      infra/scripts/bootstrap-admin.sh --check
#      infra/scripts/bootstrap-admin.sh --dry-run \
#          --email admin@example.de --password '<12+ chars>' \
#          --name "Admin" --tenant "Kanzlei Muster" --account-type COMPANY
#
#  Exit codes:
#      0  registration accepted by the backend (HTTP 2xx)
#      1  the call failed, or the backend rejected the request
#      2  usage error / refused
#      3  NOT AVAILABLE — the endpoint this script depends on is not deployed
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROG="$(basename "$0")"

log()    { printf '%s: %s\n' "$PROG" "$*"; }
warn()   { printf '%s: WARNING: %s\n' "$PROG" "$*" >&2; }
die()    { printf '%s: ERROR: %s\n' "$PROG" "$*" >&2; exit 1; }
refuse() { printf '%s: REFUSED: %s\n' "$PROG" "$*" >&2; exit 2; }
unavailable() { printf '%s: NOT AVAILABLE — %s\n' "$PROG" "$*" >&2; exit 3; }

usage() {
    cat <<'USAGE'
Usage: bootstrap-admin.sh [options]

Options:
  --check                  only report whether the backend offers account creation
  --email <address>        e-mail address of the first administrator   (required)
  --password <secret>      password, min 12 chars per the backend policy (required;
                           prefer BC_ADMIN_PASSWORD so it stays out of the process list)
  --name <full name>       display name of the administrator
  --tenant <name>          organisation/tenant name to create
  --account-type <type>    PRIVATE_PERSON | FAMILY_HOUSEHOLD | FREELANCER | COMPANY |
                           COMPANY_TEAM | EXTERNAL_REPRESENTATIVE |
                           HIGH_SECURITY_ORGANIZATION   (default: COMPANY)
  --locale <tag>           e.g. de-DE        (default: de-DE)
  --time-zone <zone>       e.g. Europe/Berlin (default: Europe/Berlin)
  --api-base <url>         backend base URL (default: $BC_API_BASE_URL or
                           http://localhost:${BC_PORT:-8080})
  --dry-run                print the request that WOULD be sent, send nothing
  -h, --help               this help

Environment:
  BC_ADMIN_EMAIL / BC_ADMIN_PASSWORD / BC_ADMIN_NAME / BC_ADMIN_TENANT /
  BC_ADMIN_ACCOUNT_TYPE
  BC_API_BASE_URL          backend base URL

Exit codes: 0 = accepted, 1 = failed/rejected, 2 = usage error or refused,
            3 = the endpoint is not available on this backend.
USAGE
}

EMAIL="${BC_ADMIN_EMAIL:-}"
PASSWORD="${BC_ADMIN_PASSWORD:-}"
NAME="${BC_ADMIN_NAME:-}"
TENANT="${BC_ADMIN_TENANT:-}"
ACCOUNT_TYPE="${BC_ADMIN_ACCOUNT_TYPE:-COMPANY}"
LOCALE="${BC_ADMIN_LOCALE:-de-DE}"
TIME_ZONE="${BC_ADMIN_TIME_ZONE:-Europe/Berlin}"
API_BASE="${BC_API_BASE_URL:-http://localhost:${BC_PORT:-8080}}"
CHECK_ONLY=0
DRY_RUN=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)     usage; exit 0 ;;
        --check)       CHECK_ONLY=1; shift ;;
        --dry-run)     DRY_RUN=1; shift ;;
        --email)       [ "$#" -ge 2 ] || die "--email needs a value"; EMAIL="$2"; shift 2 ;;
        --password)    [ "$#" -ge 2 ] || die "--password needs a value"; PASSWORD="$2"; shift 2 ;;
        --name)        [ "$#" -ge 2 ] || die "--name needs a value"; NAME="$2"; shift 2 ;;
        --tenant)      [ "$#" -ge 2 ] || die "--tenant needs a value"; TENANT="$2"; shift 2 ;;
        --account-type) [ "$#" -ge 2 ] || die "--account-type needs a value"; ACCOUNT_TYPE="$2"; shift 2 ;;
        --locale)      [ "$#" -ge 2 ] || die "--locale needs a value"; LOCALE="$2"; shift 2 ;;
        --time-zone)   [ "$#" -ge 2 ] || die "--time-zone needs a value"; TIME_ZONE="$2"; shift 2 ;;
        --api-base)    [ "$#" -ge 2 ] || die "--api-base needs a value"; API_BASE="$2"; shift 2 ;;
        *)             echo "$PROG: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

command -v curl >/dev/null 2>&1 || die "curl not found in PATH (needed to call the backend)"

# -----------------------------------------------------------------------------
# 1. Is the backend reachable at all?
# -----------------------------------------------------------------------------
HEALTH_URL="${API_BASE%/}/actuator/health"
HEALTH_BODY=""
if HEALTH_BODY="$(curl -fsS --max-time 10 "$HEALTH_URL" 2>/dev/null)"; then
    log "backend is reachable at $API_BASE (health: $(printf '%s' "$HEALTH_BODY" | tr -d '\n' | head -c 120))"
else
    warn "backend health endpoint $HEALTH_URL did not answer — is the api running?"
fi

# -----------------------------------------------------------------------------
# 2. Detect the account-creation endpoint
# -----------------------------------------------------------------------------
# The contract this script depends on (verified in the backend source):
#     POST /api/v1/auth/register   — public, creates tenant + workspace + OWNER
# Detection order:
#   (a) the generated OpenAPI document (/api-docs), which is authoritative;
#   (b) an OPTIONS probe of the exact path (a 404/405 means it is not deployed);
#   (c) the built jar, as a last resort.
REGISTER_PATH="/api/v1/auth/register"
REGISTER_URL="${API_BASE%/}${REGISTER_PATH}"
IDENTIFIED=""
DETAIL=""

OPENAPI_URL="${API_BASE%/}/api-docs"
if OPENAPI_BODY="$(curl -fsS --max-time 10 "$OPENAPI_URL" 2>/dev/null)"; then
    if printf '%s' "$OPENAPI_BODY" | grep -q "$REGISTER_PATH"; then
        IDENTIFIED="register"
        DETAIL="POST $REGISTER_URL is documented in $OPENAPI_URL"
    else
        DETAIL="the OpenAPI document at $OPENAPI_URL does not document $REGISTER_PATH"
    fi
else
    DETAIL="the OpenAPI document is not reachable at $OPENAPI_URL (the api may be down, or /api-docs is not exposed)"
fi

if [ -z "$IDENTIFIED" ]; then
    # `-w '%{http_code}'` already prints 000 on a connection failure, so no
    # fallback echo is appended (that would produce "000000").
    OPTIONS_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS --max-time 10 "$REGISTER_URL" 2>/dev/null || true)"
    [ -n "$OPTIONS_STATUS" ] || OPTIONS_STATUS=000
    case "$OPTIONS_STATUS" in
        200|204)    IDENTIFIED="register"; DETAIL="OPTIONS $REGISTER_URL -> HTTP $OPTIONS_STATUS (route exists)" ;;
        401|403|405) IDENTIFIED="register"; DETAIL="OPTIONS $REGISTER_URL -> HTTP $OPTIONS_STATUS (route exists; OPTIONS may be handled by the security filter chain)" ;;
        404)        DETAIL="OPTIONS $REGISTER_URL -> HTTP 404 (route not deployed)" ;;
        000)        DETAIL="OPTIONS $REGISTER_URL -> no response (backend unreachable)" ;;
        *)          DETAIL="OPTIONS $REGISTER_URL -> HTTP $OPTIONS_STATUS (inconclusive)" ;;
    esac
fi

JAR="${REPO_ROOT}/services/api/target/burokratie-copilot-api.jar"
if [ -z "$IDENTIFIED" ] && [ -f "$JAR" ]; then
    # Look INSIDE the nested application classes, not at the outer jar: a Spring
    # Boot fat jar keeps application classes under BOOT-INF/classes/.
    if unzip -l "$JAR" 'BOOT-INF/classes/*' 2>/dev/null | grep -q 'AuthController'; then
        IDENTIFIED="register"
        DETAIL="the built jar contains identity/web/AuthController (the api is built but not answering)"
    fi
fi

# -----------------------------------------------------------------------------
# 3. Report
# -----------------------------------------------------------------------------
if [ -z "$IDENTIFIED" ]; then
    cat >&2 <<EOF

$PROG: NOT AVAILABLE (exit 3)

  The account-creation endpoint this script uses is not deployed on this backend.

  Verified on:  $(date -u +%Y-%m-%dT%H:%M:%SZ)
  Target:       $REGISTER_URL
  Probe result: $DETAIL

  Why this script does not insert rows into the database instead:
    * Creating the owner account requires the application's own Argon2id password
      hashing, audit event, and tenant/workspace/membership rules. Writing rows
      directly would bypass them and produce an account the application may not
      accept — that is fake functionality (spec §1.2).

  What to do:
    1. start the backend and re-run:  $PROG --check
    2. if the backend is up but exposes a different path, verify it against
       ${API_BASE%/}/api-docs and report the mismatch — the script's contract
       may need updating.

EOF
    exit 3
fi

log "account-creation endpoint: POST $REGISTER_URL"
log "detail: $DETAIL"

if [ "$CHECK_ONLY" -eq 1 ]; then
    log "--check: account creation is available; re-run without --check to create the first owner"
    exit 0
fi

# -----------------------------------------------------------------------------
# 4. Validate the input before sending anything
# -----------------------------------------------------------------------------
[ -n "$EMAIL" ] || refuse "--email is required (or set BC_ADMIN_EMAIL)"
[ -n "$PASSWORD" ] || refuse "--password is required (or set BC_ADMIN_PASSWORD) — the backend enforces a minimum length of 12 characters"
[ -n "$TENANT" ] || refuse "--tenant is required (or set BC_ADMIN_TENANT): registration creates a tenant, and an unnamed tenant is not usable"

case "$EMAIL" in
    *@*.*) : ;;
    *) refuse "--email does not look like an e-mail address: $EMAIL" ;;
esac

if [ "${#PASSWORD}" -lt 12 ]; then
    refuse "the password is shorter than 12 characters — the backend policy (burokratie.security.password.min-length) would reject it"
fi

case "$ACCOUNT_TYPE" in
    PRIVATE_PERSON|FAMILY_HOUSEHOLD|FREELANCER|COMPANY|COMPANY_TEAM|EXTERNAL_REPRESENTATIVE|HIGH_SECURITY_ORGANIZATION) : ;;
    *) refuse "--account-type '$ACCOUNT_TYPE' is not one of the values the backend maps (see AuthService.workspaceModeFor)" ;;
esac

# JSON-escape the free-text fields: a quote or backslash in a company name must
# not be able to break the request body.
json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

PAYLOAD="$(printf '{"email":"%s","password":"%s","displayName":"%s","accountType":"%s","tenantName":"%s","locale":"%s","timeZone":"%s"}' \
    "$(json_escape "$EMAIL")" \
    "$(json_escape "$PASSWORD")" \
    "$(json_escape "${NAME:-$EMAIL}")" \
    "$(json_escape "$ACCOUNT_TYPE")" \
    "$(json_escape "$TENANT")" \
    "$(json_escape "$LOCALE")" \
    "$(json_escape "$TIME_ZONE")")"

# The password must never be echoed: the printed payload is masked explicitly.
MASKED_PAYLOAD="$(printf '%s' "$PAYLOAD" | sed 's/"password":"[^"]*"/"password":"<redacted>"/')"

if [ "$DRY_RUN" -eq 1 ]; then
    log "--dry-run: would send POST $REGISTER_URL"
    log "payload: $MASKED_PAYLOAD"
    log "nothing was sent"
    exit 0
fi

# -----------------------------------------------------------------------------
# 5. CSRF handshake
# -----------------------------------------------------------------------------
# The API uses the double-submit cookie pattern
# (SecurityConfiguration: CookieCsrfTokenRepository, cookie XSRF-TOKEN, header
# X-XSRF-TOKEN). Registration is a state-changing request, so without this
# handshake the backend correctly answers HTTP 403 — a real deployment behaves
# exactly the same way, which is why the script performs it instead of
# disabling protection.
CSRF_URL="${API_BASE%/}/api/v1/auth/csrf"
COOKIE_JAR="$(mktemp "${TMPDIR:-/tmp}/bc-cookies.XXXXXX")"
RESPONSE_FILE="$(mktemp "${TMPDIR:-/tmp}/bc-bootstrap.XXXXXX")"
trap 'rm -f "$RESPONSE_FILE" "$COOKIE_JAR"' EXIT

CSRF_STATUS="$(curl -sS -o /dev/null -c "$COOKIE_JAR" -w '%{http_code}' --max-time 15 "$CSRF_URL" 2>/dev/null || true)"
[ -n "$CSRF_STATUS" ] || CSRF_STATUS=000
if [ "$CSRF_STATUS" != "200" ] && [ "$CSRF_STATUS" != "204" ]; then
    die "the CSRF handshake at $CSRF_URL failed (HTTP $CSRF_STATUS) — cannot safely send a state-changing request"
fi
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)"
CSRF_HEADER_ARGS=()
if [ -n "$CSRF_TOKEN" ]; then
    log "CSRF token acquired (${#CSRF_TOKEN} chars); the temporary cookie jar is removed on exit"
    CSRF_HEADER_ARGS=(-H "X-XSRF-TOKEN: ${CSRF_TOKEN}")
else
    # OBSERVED BEHAVIOUR (2026-10-11, verified against a running build): the
    # configured CookieCsrfTokenRepository writes the XSRF-TOKEN cookie only when
    # the CsrfToken is actually *read* during the request, and
    # GET /api/v1/auth/csrf returns 204 without touching it. A client therefore
    # never receives the token, and every state-changing request is answered with
    # HTTP 403 — including sign-in from a real browser.
    #
    # This is a backend gap, not something a deployment script may paper over:
    # the handler must resolve the token (e.g. accept `CsrfToken` as a method
    # argument or use CsrfTokenRequestAttributeHandler with eager loading) so that
    # the cookie is set. The script continues so that it still works on a backend
    # where the handshake is fixed, and reports the 403 case precisely.
    warn "the CSRF handshake returned no XSRF-TOKEN cookie."
    warn "If the backend answers HTTP 403 to the registration below, this is the reason:"
    warn "  the api does not currently deliver a CSRF token to the client (see the note in"
    warn "  this script and docs/integrations.md). Fix the backend; do NOT disable CSRF."
fi

# -----------------------------------------------------------------------------
# 6. Register the first owner
# -----------------------------------------------------------------------------
log "POST $REGISTER_URL (payload: $MASKED_PAYLOAD)"

STATUS="$(curl -sS -o "$RESPONSE_FILE" -w '%{http_code}' \
    --max-time 30 \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -X POST "$REGISTER_URL" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    "${CSRF_HEADER_ARGS[@]+"${CSRF_HEADER_ARGS[@]}"}" \
    --data "$PAYLOAD" 2>/dev/null || true)"
[ -n "$STATUS" ] || STATUS=000

case "$STATUS" in
    2??)
        log "account created and accepted by the backend (HTTP $STATUS)"
        log "server response: $(head -c 400 "$RESPONSE_FILE")"
        cat <<EOF

  NEXT STEPS — the account is NOT ready for normal use yet:

    1. E-mail verification is required. The response contains "nextStep":
         VERIFY_EMAIL                            -> a verification mail was sent
         VERIFY_EMAIL_PENDING_MAIL_CONFIGURATION -> NO MAIL WAS SENT (no SMTP sender
                                                    configured); the verification token
                                                    must be retrieved by the operator
    2. The registered user owns the tenant and holds the OWNER role.
    3. Sign in at ${BC_PUBLIC_BASE_URL:-http://localhost:3000}/login and change the
       password if it was generated rather than chosen.

EOF
        exit 0 ;;
    000)
        die "no response from $REGISTER_URL — the api is not reachable" ;;
    409)
        die "HTTP 409 — the e-mail address is already registered. Registration is not idempotent; use the password-reset flow instead of re-registering. Server response: $(head -c 300 "$RESPONSE_FILE")" ;;
    400)
        die "HTTP 400 — the backend rejected the payload (validation). Server response: $(head -c 400 "$RESPONSE_FILE")" ;;
    403)
        die "HTTP 403 — the backend refused the registration.
       Most likely cause: CSRF. The API enables CSRF protection (double-submit cookie
       XSRF-TOKEN -> header X-XSRF-TOKEN), but GET /api/v1/auth/csrf does not currently
       deliver the cookie, so no client can obtain a token. Verify with:
         curl -si \"${API_BASE%/}/api/v1/auth/csrf\" | grep -i set-cookie
       Fix the backend (the CSRF handler must resolve the token) — do NOT disable CSRF
       protection, and do not create the account by writing to the database directly.
       Secondary causes: registration closed at the edge, or a missing/incorrect Origin
       when the request is cross-origin (docs/deployment.md)." ;;
    429)
        die "HTTP 429 — rate limited. Wait and retry, or create the account through the planned bootstrap-admin flow." ;;
    *)
        die "HTTP $STATUS — registration failed. Server response: $(head -c 400 "$RESPONSE_FILE")" ;;
esac
