#!/usr/bin/env bash
# Pull-based continuous deployment, run every minute from cron on the server (no inbound ports, no CI secrets):
#   * looks at origin/main; nothing new -> exits silently;
#   * a new commit -> database dump, rebuild only what changed, restart, wait for health;
#   * unhealthy after the update -> the previous images are put back and that commit is not retried.
# Log: <root>/deploy.log.   Install:  scripts/auto-deploy.sh --install-cron
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

compose() {
  docker compose -p burokratie --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.prod.yml \
    -f infra/docker-compose.host-proxy.yml -f infra/docker-compose.portable.yml "$@"
}

wait_healthy() { # wait_healthy <seconds>
  local end=$((SECONDS + $1)) a w
  while (( SECONDS < end )); do
    a="$(docker inspect -f '{{.State.Health.Status}}' bc-api 2>/dev/null)"
    w="$(docker inspect -f '{{.State.Health.Status}}' bc-web 2>/dev/null)"
    [[ "$a" == healthy && "$w" == healthy ]] && return 0
    sleep 5
  done
  return 1
}

main() {
  cd "$ROOT"
  exec 9>"$ROOT/.deploy.lock"
  flock -n 9 || exit 0 # one deployment at a time
  log() { echo "$(date -Is) $*" >> "$ROOT/deploy.log"; }
  local branch=main new cur changed rebuild=false
  git fetch -q origin "$branch" 2>>"$ROOT/deploy.log" || { log "git fetch failed"; exit 1; }
  new="$(git rev-parse "origin/$branch")"
  cur="$(git rev-parse HEAD)"
  [[ "$new" == "$cur" ]] && exit 0
  # A commit that already failed is not retried until a newer one arrives.
  [[ -f .deploy-failed && "$(cat .deploy-failed)" == "$new" ]] && exit 0

  log "deploy ${cur:0:8} -> ${new:0:8}: $(git log -1 --format=%s "$new")"
  changed="$(git diff --name-only "$cur" "$new")"
  grep -qE '^(apps/|services/|infra/docker)' <<<"$changed" && rebuild=true
  git reset -q --hard "$new"

  export BC_DATA_DIR="$ROOT/data"
  local pguser pgdb
  pguser="$(grep -E '^POSTGRES_USER=' infra/.env | cut -d= -f2)"; pguser="${pguser:-burokratie}"
  pgdb="$(grep -E '^POSTGRES_DB=' infra/.env | cut -d= -f2)"; pgdb="${pgdb:-burokratie}"

  # Safety net before anything changes: a restorable dump; the newest 10 are kept.
  mkdir -p backups
  if docker exec bc-postgres pg_dump -U "$pguser" -d "$pgdb" -Fc > "backups/pre-deploy-$(date +%Y%m%d-%H%M%S).dump" 2>>"$ROOT/deploy.log"; then
    ls -1t backups/pre-deploy-*.dump 2>/dev/null | tail -n +11 | xargs -r rm -f
  else
    log "WARNING: database dump failed; continuing"
  fi

  if $rebuild; then
    for img in api web; do docker tag "burokratie-local/$img:local" "burokratie-local/$img:prev" 2>/dev/null; done
    log "building images"
    if ! scripts/portable.sh build >> "$ROOT/deploy.log" 2>&1; then
      log "BUILD FAILED - the running version is untouched"
      echo "$new" > .deploy-failed
      git reset -q --hard "$cur"
      exit 1
    fi
  fi

  scripts/portable.sh up >> "$ROOT/deploy.log" 2>&1
  if wait_healthy 300; then
    rm -f .deploy-failed
    log "deployed ${new:0:8} OK"
  else
    log "UNHEALTHY after the update - rolling back to ${cur:0:8}"
    for img in api web; do
      if docker image inspect "burokratie-local/$img:prev" >/dev/null 2>&1; then docker tag "burokratie-local/$img:prev" "burokratie-local/$img:local"; fi
    done
    echo "$new" > .deploy-failed
    git reset -q --hard "$cur"
    compose up -d --force-recreate >> "$ROOT/deploy.log" 2>&1
    log "rolled back (database migrations only go forward: restore from backups/ if the schema changed)"
    exit 1
  fi
}

if [[ "${1:-}" == "--install-cron" ]]; then
  line="* * * * * $ROOT/scripts/auto-deploy.sh"
  ( crontab -l 2>/dev/null | grep -vF "$ROOT/scripts/auto-deploy.sh"; echo "$line" ) | crontab -
  echo "installed: $line"
  exit 0
fi
main "$@"
exit
