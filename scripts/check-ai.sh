#!/usr/bin/env bash
# Checks the AI settings in infra/.env without printing the key:
#   1. the provider can be reached,  2. the key is accepted,  3. the model understands a picture.
# Used by enable-ai.sh; can be run on its own:  scripts/check-ai.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${1:-$ROOT/infra/.env}"

get() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }

PROVIDER="$(get BC_AI_PROVIDER)"
BASE="$(get BC_AI_BASE_URL)"
KEY="$(get BC_AI_API_KEY)"
MODEL="$(get BC_AI_MODEL)"
# A 1x1 pixel picture: enough to learn whether the model accepts pictures at all.
PNG="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

[[ -n "$KEY" ]] || { echo "FEHLER: BC_AI_API_KEY ist leer in $ENV_FILE"; exit 2; }
BODY="$(mktemp)"; trap 'rm -f "$BODY"' EXIT

case "$PROVIDER" in
  openai-compatible)
    URL="${BASE%/}/chat/completions"
    PAYLOAD="{\"model\":\"$MODEL\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Antworte mit OK.\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,$PNG\"}}]}]}"
    CODE="$(curl -sS -m 60 -o "$BODY" -w '%{http_code}' -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d "$PAYLOAD" "$URL" 2>&1)" ;;
  anthropic)
    URL="${BASE:-https://api.anthropic.com}"; URL="${URL%/}/v1/messages"
    PAYLOAD="{\"model\":\"$MODEL\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"$PNG\"}},{\"type\":\"text\",\"text\":\"Antworte mit OK.\"}]}]}"
    CODE="$(curl -sS -m 60 -o "$BODY" -w '%{http_code}' -H "x-api-key: $KEY" -H 'anthropic-version: 2023-06-01' -H 'Content-Type: application/json' -d "$PAYLOAD" "$URL" 2>&1)" ;;
  *) echo "FEHLER: BC_AI_PROVIDER ist '$PROVIDER' (erwartet: openai-compatible oder anthropic)"; exit 2 ;;
esac

# The answer of the provider never contains the key, but it is cut to the first 300 characters anyway.
SNIPPET="$(head -c 300 "$BODY" 2>/dev/null | tr '\n' ' ')"
case "$CODE" in
  200) echo "OK: Anbieter erreichbar, Schlüssel akzeptiert, Modell '$MODEL' versteht Bilder."; exit 0 ;;
  401|403) echo "FEHLER ($CODE): Der Schlüssel wurde abgelehnt. Prüfen Sie ihn, und bei einem Europa-Projekt die Adresse ($URL)." ;;
  404) echo "FEHLER (404): Adresse oder Modell nicht gefunden: $URL / $MODEL. Antwort: $SNIPPET" ;;
  400) echo "FEHLER (400): Das Modell '$MODEL' nimmt vielleicht keine Bilder an, oder die Anfrage wurde abgelehnt. Antwort: $SNIPPET" ;;
  429) echo "FEHLER (429): Zu viele Anfragen oder kein Guthaben beim Anbieter. Antwort: $SNIPPET" ;;
  *)   echo "FEHLER ($CODE): $SNIPPET" ;;
esac
exit 1
