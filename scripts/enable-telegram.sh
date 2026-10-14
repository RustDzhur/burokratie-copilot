#!/usr/bin/env bash
# Switches the Telegram informer on: you create the bot once in Telegram, paste its token here, and the platform can
# tell every person who links their chat what happens with their documents.
#
#   1. In Telegram open @BotFather, send /newbot, choose a name and a user name (it must end in "bot").
#   2. BotFather answers with a token (123456:ABC...). Run   scripts/enable-telegram.sh   and paste it.
#   3. In the platform: Integrations -> Telegram -> "Connect", open the link, press Start in Telegram.
#
#   scripts/enable-telegram.sh --off      switches the informer off again
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/infra/.env"
[[ -f "$ENV_FILE" ]] || { echo "infra/.env nicht gefunden ($ENV_FILE)"; exit 2; }
export BC_DATA_DIR="$ROOT/data"   # the same as scripts/portable.sh: the compose files need it

compose() {
  docker compose -p burokratie --env-file "$ROOT/infra/.env" -f "$ROOT/infra/docker-compose.yml" \
    -f "$ROOT/infra/docker-compose.prod.yml" -f "$ROOT/infra/docker-compose.host-proxy.yml" \
    -f "$ROOT/infra/docker-compose.portable.yml" "$@"
}

set_var() {
  local name="$1" value="$2" tmp
  tmp="$(mktemp)"
  grep -vE "^${name}=" "$ENV_FILE" > "$tmp" || true
  printf '%s=%s\n' "$name" "$value" >> "$tmp"
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
}

if [[ "${1:-}" == "--off" ]]; then
  set_var BC_TELEGRAM_BOT_TOKEN ""
  compose up -d api worker && echo "Der Telegram-Informant ist ausgeschaltet."
  exit $?
fi

read -rsp "Bot-Token von @BotFather (Eingabe bleibt unsichtbar): " TOKEN; echo
[[ -n "$TOKEN" ]] || { echo "Kein Token eingegeben."; exit 2; }

# The token is checked with Telegram before anything is written: getMe answers with the bot's user name.
ANSWER="$(curl -sS -m 20 "https://api.telegram.org/bot${TOKEN}/getMe" 2>&1)"
USERNAME="$(printf '%s' "$ANSWER" | sed -n 's/.*"username":"\([^"]*\)".*/\1/p')"
if [[ -z "$USERNAME" ]]; then
  echo "FEHLER: Telegram hat den Token nicht akzeptiert. Prüfen Sie ihn (ohne Leerzeichen, mit dem Doppelpunkt)."
  exit 1
fi

cp "$ENV_FILE" "$ENV_FILE.before-telegram"
chmod 600 "$ENV_FILE.before-telegram" 2>/dev/null || true
set_var BC_TELEGRAM_BOT_TOKEN "$TOKEN"
set_var BC_TELEGRAM_BOT_USERNAME "$USERNAME"
unset TOKEN
echo "Bot @$USERNAME erkannt. Starte die Plattform neu ..."
compose up -d api worker && echo "Fertig. In der Plattform: Integrationen -> Telegram -> Verbinden."
