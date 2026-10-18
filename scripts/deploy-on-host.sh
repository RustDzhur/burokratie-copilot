#!/usr/bin/env bash
# =============================================================================
#  Deployment auf dem Server — ein Befehl, keine Vorkenntnisse nötig
# =============================================================================
#  Auf dem Server ausführen:
#
#      curl -fsSL https://raw.githubusercontent.com/RustDzhur/burokratie-copilot/main/scripts/deploy-on-host.sh | bash
#
#  oder, wenn das Repository schon dort liegt:
#
#      bash scripts/deploy-on-host.sh
#
#  Das Skript ist absichtlich vorsichtig: es überschreibt keine Secrets, es
#  baut erst und startet dann, und es sagt klar, was fehlt, statt zu raten.
# =============================================================================
set -euo pipefail

REPO="${BC_REPO:-https://github.com/RustDzhur/burokratie-copilot.git}"
TARGET="${BC_DEPLOY_PATH:-/srv/burokratie-copilot}"
BRANCH="${BC_DEPLOY_REF:-main}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31mFEHLER:\033[0m %s\n' "$*" >&2; exit 1; }

say "1/6 Werkzeuge prüfen"
command -v git >/dev/null || die "git fehlt. Installieren: apt install git / dnf install git"
command -v docker >/dev/null || die "docker fehlt. Siehe https://docs.docker.com/engine/install/"
docker compose version >/dev/null 2>&1 || die "docker compose (Plugin) fehlt. Installieren: apt install docker-compose-plugin"
echo "git, docker und compose sind vorhanden."

say "2/6 Quelltext holen ($BRANCH)"
if [ -d "$TARGET/.git" ]; then
  git -C "$TARGET" fetch --all --prune
  git -C "$TARGET" checkout --force "$BRANCH"
  git -C "$TARGET" reset --hard "origin/$BRANCH"
else
  mkdir -p "$TARGET"
  git clone --branch "$BRANCH" "$REPO" "$TARGET"
fi
echo "Stand: $(git -C "$TARGET" rev-parse --short HEAD)"

say "3/6 Umgebungsdatei prüfen"
cd "$TARGET"
if [ -f infra/.env ]; then
  echo "infra/.env ist vorhanden und wird NICHT überschrieben."
else
  cat > infra/.env <<ENVFILE
# Automatisch erzeugt von scripts/deploy-on-host.sh.
# Diese Datei gehört nicht ins Repository (siehe .gitignore).
POSTGRES_DB=burokratie
POSTGRES_USER=burokratie
POSTGRES_PASSWORD=$(openssl rand -base64 24 2>/dev/null || head -c 32 /dev/urandom | base64)
BC_DB_PASSWORD=$(grep '^POSTGRES_PASSWORD=' infra/.env 2>/dev/null | cut -d= -f2- || true)
BC_ENCRYPTION_KEY=$(openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64)
MINIO_ROOT_USER=burokratie
MINIO_ROOT_PASSWORD=$(openssl rand -base64 24 2>/dev/null || head -c 32 /dev/urandom | base64)
BC_S3_ACCESS_KEY=$(openssl rand -base64 20 2>/dev/null || head -c 20 /dev/urandom | base64)
BC_S3_SECRET_KEY=$(openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64)
BC_IMAGE_TAG=local
BC_REGISTRY=local
BC_SERVER_NAMES=localhost
BC_PUBLIC_BASE_URL=http://localhost
TZ=Europe/Berlin
ENVFILE
  # Das Passwort steht zweimal in der Datei und muss identisch sein.
  PASS=$(grep '^POSTGRES_PASSWORD=' infra/.env | cut -d= -f2-)
  sed -i "s|^BC_DB_PASSWORD=.*|BC_DB_PASSWORD=$PASS|" infra/.env
  chmod 600 infra/.env
  echo "infra/.env neu erzeugt (Rechte 600). Bitte BC_SERVER_NAMES und BC_PUBLIC_BASE_URL auf den echten Host setzen."
fi

say "4/6 Images bauen"
docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.prod.yml build

say "5/6 Stack starten"
docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.prod.yml --profile proxy up -d

say "6/6 Gesundheit prüfen"
for attempt in $(seq 1 40); do
  if docker exec bc-api curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "API ist bereit (Versuch $attempt)."
    docker ps --format '{{.Names}}\t{{.Status}}'
    printf '\nFertig. Der Dienst lauscht auf Port 80/443 dieses Hosts.\n'
    exit 0
  fi
  sleep 10
done

docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.prod.yml logs --tail=40 api || true
die "Die API wurde nicht bereit. Die Logs oben nennen den Grund."
