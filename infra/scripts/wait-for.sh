#!/usr/bin/env bash
# =============================================================================
#  wait-for.sh — wait until one or more TCP host:port targets accept connections
# =============================================================================
#  Purpose: gate a startup sequence on a dependency that has no Docker
#  healthcheck (an external database, an object-storage endpoint, an SMTP relay,
#  ...). Inside Docker Compose prefer `depends_on: condition: service_healthy`;
#  this script exists for the cases a Compose healthcheck cannot express.
#
#  Usage:
#      infra/scripts/wait-for.sh <host:port> [timeout-seconds]
#      infra/scripts/wait-for.sh postgres:5432 60
#      infra/scripts/wait-for.sh --timeout 90 postgres:5432 minio:9000
#
#  Exit codes:
#      0  every target accepted a TCP connection
#      1  the timeout expired before that happened
#      2  usage error
#
#  Implementation note: bash's /dev/tcp is used, so no nc/curl/python is needed.
#  A successful handshake proves that something LISTENS on the port — it does not
#  prove that the service is healthy or that the credentials are valid. Use a
#  real readiness probe (e.g. /actuator/health/readiness) for that.
#
#  Compatible with bash 3.2 (macOS) — no associative arrays, no ${var,,}.
# =============================================================================
set -euo pipefail

PROG="$(basename "$0")"

usage() {
    cat <<'USAGE'
Usage: wait-for.sh [options] <host:port> [more host:port ...] [timeout-seconds]

Options:
  --timeout <seconds>   total wait budget (default: 30, or $WAIT_TIMEOUT)
  --interval <seconds>  polling interval (default: 1, or $WAIT_INTERVAL)
  --quiet               suppress progress output
  -h, --help            this help

Environment:
  WAIT_TIMEOUT    default timeout in seconds
  WAIT_INTERVAL   default polling interval in seconds

Exit codes: 0 = ready, 1 = timeout, 2 = usage error.
USAGE
}

QUIET=0
TIMEOUT=""
INTERVAL=""
TARGETS=()
POSITIONAL_TIMEOUT=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)  usage; exit 0 ;;
        --quiet)    QUIET=1; shift ;;
        --timeout)  [ "$#" -ge 2 ] || { echo "$PROG: --timeout needs a value" >&2; exit 2; }; TIMEOUT="$2"; shift 2 ;;
        --interval) [ "$#" -ge 2 ] || { echo "$PROG: --interval needs a value" >&2; exit 2; }; INTERVAL="$2"; shift 2 ;;
        --)         shift; break ;;
        -*)         echo "$PROG: unknown option: $1" >&2; usage >&2; exit 2 ;;
        *)          TARGETS+=("$1"); shift ;;
    esac
done
# Anything after `--` is positional as well.
while [ "$#" -gt 0 ]; do TARGETS+=("$1"); shift; done

if [ "${#TARGETS[@]}" -eq 0 ]; then
    echo "$PROG: at least one <host:port> target is required" >&2
    usage >&2
    exit 2
fi

# A trailing bare integer is accepted as the timeout (convenience form:
# `wait-for.sh db:5432 cache:6379 60`).
if [ "${#TARGETS[@]}" -ge 2 ]; then
    LAST="${TARGETS[$((${#TARGETS[@]} - 1))]}"
    case "$LAST" in
        *[!0-9]*) : ;;
        '')       : ;;
        *)
            POSITIONAL_TIMEOUT="$LAST"
            # Drop the last element without `unset` (which would leave a hole).
            COUNT=$((${#TARGETS[@]} - 1))
            OTHERS=()
            i=0
            while [ "$i" -lt "$COUNT" ]; do OTHERS+=("${TARGETS[$i]}"); i=$((i + 1)); done
            TARGETS=("${OTHERS[@]}")
            ;;
    esac
fi
[ -n "$TIMEOUT" ] || TIMEOUT="${POSITIONAL_TIMEOUT:-${WAIT_TIMEOUT:-30}}"
[ -n "$INTERVAL" ] || INTERVAL="${WAIT_INTERVAL:-1}"

case "$TIMEOUT" in
    ''|*[!0-9]*) echo "$PROG: timeout must be a non-negative integer (got: $TIMEOUT)" >&2; exit 2 ;;
esac
case "$INTERVAL" in
    ''|*[!0-9]*) echo "$PROG: interval must be a non-negative integer (got: $INTERVAL)" >&2; exit 2 ;;
esac

probe() {
    # probe <host> <port> → 0 when the TCP handshake succeeds
    local host="$1" port="$2"
    ( exec 3<>"/dev/tcp/${host}/${port}" ) >/dev/null 2>&1
}

# Validate every target before waiting so that a typo fails immediately instead
# of consuming the whole timeout budget.
HOSTS=()
PORTS=()
for target in "${TARGETS[@]}"; do
    case "$target" in
        \[*\]:*) host="${target%%]:*}"; host="${host#[}"; port="${target##*:}" ;;
        *:*:*)   echo "$PROG: ambiguous target '$target' — use [ipv6]:port" >&2; exit 2 ;;
        *:*)     host="${target%:*}"; port="${target##*:}" ;;
        *)       echo "$PROG: target must be host:port (got: '$target')" >&2; exit 2 ;;
    esac
    if [ -z "$host" ] || [ -z "$port" ]; then
        echo "$PROG: target must be host:port (got: '$target')" >&2
        exit 2
    fi
    case "$port" in
        *[!0-9]*) echo "$PROG: port must be numeric (got: '$port')" >&2; exit 2 ;;
    esac
    HOSTS+=("$host")
    PORTS+=("$port")
done

START="$(date +%s)"
DEADLINE=$((START + TIMEOUT))

while :; do
    ALL_READY=1
    PENDING=""
    i=0
    while [ "$i" -lt "${#HOSTS[@]}" ]; do
        if ! probe "${HOSTS[$i]}" "${PORTS[$i]}"; then
            ALL_READY=0
            PENDING="${PENDING} ${HOSTS[$i]}:${PORTS[$i]}"
        fi
        i=$((i + 1))
    done

    if [ "$ALL_READY" -eq 1 ]; then
        if [ "$QUIET" -eq 0 ]; then
            printf '%s: all %s target(s) reachable\n' "$PROG" "${#HOSTS[@]}"
        fi
        exit 0
    fi

    NOW="$(date +%s)"
    if [ "$NOW" -ge "$DEADLINE" ]; then
        printf '%s: TIMEOUT after %ss — still unreachable:%s\n' "$PROG" "$TIMEOUT" "$PENDING" >&2
        exit 1
    fi

    if [ "$QUIET" -eq 0 ]; then
        printf '%s: waiting for%s (%ss left)\n' "$PROG" "$PENDING" "$((DEADLINE - NOW))"
    fi
    sleep "$INTERVAL"
done
