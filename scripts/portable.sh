#!/usr/bin/env bash
# One place, one command for a self-contained installation (compose project `burokratie`).
#
#   scripts/portable.sh up        build images if missing and start everything
#   scripts/portable.sh backup    database dump into backups/ (cron: daily 03:15)
#   scripts/portable.sh build     (re)build the images
#   scripts/portable.sh down      stop (data stays)
#   scripts/portable.sh status    containers and health
#   scripts/portable.sh logs [svc]
#   scripts/portable.sh pack [--with-images]   archive the whole directory (the stack is stopped for the copy, so
#                                              the database is consistent) into ../burokratie-copilot-<date>.tar.gz
#   scripts/portable.sh unpack <archive> [dir]   on a NEW server: extract keeping the database owner
#   scripts/portable.sh restore   on a NEW server, after unpacking: load images (if shipped) and start
#
# Moving to another server:  pack  ->  copy the archive  ->  unpack  ->  cd burokratie-copilot
#                            ->  scripts/portable.sh restore  ->  point the reverse proxy at 127.0.0.1:$BC_WEB_PORT
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export BC_DATA_DIR="$ROOT/data"
ENV_FILE=infra/.env
[[ -f $ENV_FILE ]] || { echo "missing $ENV_FILE (copy infra/.env.example and fill it in)"; exit 1; }
compose() {
  docker compose -p burokratie --env-file "$ENV_FILE" \
    -f infra/docker-compose.yml -f infra/docker-compose.prod.yml \
    -f infra/docker-compose.host-proxy.yml -f infra/docker-compose.portable.yml "$@"
}
prepare_dirs() {
  mkdir -p data/postgres data/app
  # The API runs as uid 10001 and cannot be chown'ed from an unprivileged login: a throw-away root container does it.
  docker run --rm -v "$BC_DATA_DIR/app:/d" alpine sh -c 'chown 10001:10001 /d' >/dev/null
}
images_present() { docker image inspect burokratie-local/api:local burokratie-local/web:local >/dev/null 2>&1; }
build_images() { docker compose -p burokratie --env-file "$ENV_FILE" -f infra/docker-compose.yml build; }

case "${1:-}" in
  backup)  # backup: a restorable database dump in <root>/backups, the newest 14 daily ones are kept
    mkdir -p backups
    pguser="$(grep -E '^POSTGRES_USER=' "$ENV_FILE" | cut -d= -f2)"; pgdb="$(grep -E '^POSTGRES_DB=' "$ENV_FILE" | cut -d= -f2)"
    out="backups/daily-$(date +%Y%m%d-%H%M%S).dump"
    docker exec bc-postgres pg_dump -U "${pguser:-burokratie}" -d "${pgdb:-burokratie}" -Fc > "$out" && echo "dump: $out ($(du -h "$out" | cut -f1))"
    ls -1t backups/daily-*.dump 2>/dev/null | tail -n +15 | xargs -r rm -f ;;
  build) build_images ;;
  up) prepare_dirs; images_present || build_images; compose up -d ;;
  down) compose down ;;
  status) compose ps ;;
  logs) shift; compose logs --tail=100 "$@" ;;
  pack)
    with_images=false; [[ "${2:-}" == "--with-images" ]] && with_images=true
    out="$ROOT/../burokratie-copilot-$(date +%Y%m%d-%H%M%S).tar.gz"
    if $with_images; then
      mkdir -p images
      docker save burokratie-local/api:local burokratie-local/web:local postgres:17 -o images/images.tar
    fi
    echo "stopping the stack for a consistent copy ..."
    compose stop
    trap 'compose start >/dev/null || true' EXIT
    # The database directory belongs to the postgres user (uid 999, mode 700): a throw-away root container reads it,
    # and hands the archive back to the calling user.
    name="$(basename "$out")"
    docker run --rm -v "$ROOT/..:/w" alpine sh -c "tar -C /w -czf /w/$name --exclude='*/node_modules' --exclude='*/target' --exclude='*/.next' --exclude='$name' '$(basename "$ROOT")' && chown $(id -u):$(id -g) /w/$name"
    echo "packed: $out  ($(du -h "$out" | cut -f1))"
    ! $with_images || unlink images/images.tar ;;
  unpack)  # unpack <archive> [target-parent-dir]: root-owned files (the database) keep their owners
    archive="$(cd "$(dirname "${2:?usage: unpack <archive> [dir]}")" && pwd)/$(basename "$2")"
    dest="$(cd "${3:-.}" && pwd)"
    docker run --rm -v "$dest:/w" -v "$archive:/a.tar.gz:ro" alpine tar -xzf /a.tar.gz -C /w
    echo "unpacked into $dest/$(tar -tzf "$archive" | head -1 | cut -d/ -f1)" ;;
  restore)
    if [[ -f images/images.tar ]]; then docker load -i images/images.tar; fi
    prepare_dirs; images_present || build_images; compose up -d ;;
  *) sed -n '2,16p' "$0"; exit 1 ;;
esac
