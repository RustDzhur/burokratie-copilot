#!/usr/bin/env bash
# Switches the AI reading on with a complete, ready configuration: you only enter the key.
#
#   scripts/enable-ai.sh                 OpenAI (gpt-4.1-mini, cheap, reads handwriting), asks for the key
#   scripts/enable-ai.sh --eu            the same, through OpenAI's European endpoint (key of a project created with
#                                        the region "Europe")
#   scripts/enable-ai.sh --anthropic     Claude instead of OpenAI
#   scripts/enable-ai.sh --off           switches the AI off again (the key stays in the file, unused)
#
# What it does: writes the BC_AI_* lines into infra/.env (replacing the old ones), checks that the provider answers and
# that the model understands pictures, and restarts only the API and worker containers. The key is typed hidden and never printed.
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

# set_var NAME VALUE: replace the line if it exists, append it otherwise
set_var() {
  local name="$1" value="$2" tmp
  tmp="$(mktemp)"
  grep -vE "^${name}=" "$ENV_FILE" > "$tmp" || true
  printf '%s=%s\n' "$name" "$value" >> "$tmp"
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
}

MODE="openai"
case "${1:-}" in
  --eu) MODE="openai-eu" ;;
  --anthropic) MODE="anthropic" ;;
  --off)
    set_var BC_AI_ENABLED false
    compose up -d api worker && echo "KI ist ausgeschaltet."
    exit $? ;;
  "") ;;
  *) echo "Unbekannte Option: $1"; exit 2 ;;
esac

read -rsp "API-Schlüssel (Eingabe bleibt unsichtbar): " KEY; echo
[[ -n "$KEY" ]] || { echo "Kein Schlüssel eingegeben."; exit 2; }

case "$MODE" in
  openai)
    PROVIDER=openai-compatible; BASE=https://api.openai.com/v1; MODEL=gpt-4.1-mini
    PRICE_IN=0.40; PRICE_OUT=1.60 ;;
  openai-eu)
    PROVIDER=openai-compatible; BASE=https://eu.api.openai.com/v1; MODEL=gpt-4.1-mini
    PRICE_IN=0.40; PRICE_OUT=1.60 ;;
  anthropic)
    PROVIDER=anthropic; BASE=; MODEL=claude-sonnet-5-5
    PRICE_IN=3.00; PRICE_OUT=15.00 ;;
esac

cp "$ENV_FILE" "$ENV_FILE.before-ai"
chmod 600 "$ENV_FILE.before-ai" 2>/dev/null || true
set_var BC_AI_ENABLED true
set_var BC_AI_PROVIDER "$PROVIDER"
set_var BC_AI_BASE_URL "$BASE"
set_var BC_AI_MODEL "$MODEL"
set_var BC_AI_API_KEY "$KEY"
# A hard ceiling per month (the platform computes the cost from the tokens and the prices below) and per day.
set_var BC_AI_MONTHLY_BUDGET_EUR 10
set_var BC_AI_PRICE_INPUT_PER_MILLION "$PRICE_IN"
set_var BC_AI_PRICE_OUTPUT_PER_MILLION "$PRICE_OUT"
# The platform reads first with its own tools; the AI is asked only when a needed value (handwriting) is left unread.
set_var BC_AI_AUTO_READ true
set_var BC_AI_AUTO_READ_DAILY_LIMIT 20
unset KEY

echo "Einstellungen geschrieben (Sicherung: infra/.env.before-ai). Prüfe die Verbindung ..."
if "$ROOT/scripts/check-ai.sh"; then
  compose up -d api worker && echo "Fertig: Die KI-Lesung ist aktiv. Monatsgrenze 10 EUR, höchstens 20 automatische Lesungen pro Tag."
else
  echo
  echo "Die Prüfung ist fehlgeschlagen; die API wurde NICHT neu gestartet. Korrigieren Sie die Zeilen BC_AI_* in infra/.env"
  echo "(oder starten Sie mit --eu bzw. --anthropic neu) und rufen Sie dieses Skript erneut auf."
  exit 1
fi
