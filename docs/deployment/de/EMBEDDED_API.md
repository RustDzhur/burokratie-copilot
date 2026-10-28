# Embedded API

**Sprachen:** [Русский](../ru/EMBEDDED_API.md) · [Deutsch](EMBEDDED_API.md) · [English](../en/EMBEDDED_API.md)

**Modell:** Das eigene Produkt ruft Dokumenterkennung, Agenten und Workflow von Firmspace
per HTTP auf. Die Oberfläche bleibt beim Kunden; Firmspace erledigt die Arbeit und liefert
das Ergebnis zurück. Das ist keine eigene Installation, sondern ein **Integrationsweg** in
den Copilot in der Cloud oder in eine Robots Runtime beim Kunden.

---

## 1. Worum es geht und für wen es gedacht ist

Embedded API passt, wenn der Kunde einen eigenen Dienst, ein Portal, ein CRM oder eine
Branchensoftware hat und dort Dokumenteingang, Erkennung, Agenten oder die ELSTER-Ausgabe
einbauen will. Das Kundensystem sendet eine Datei oder einen Befehl und erhält JSON; alles
Weitere übernimmt Firmspace. Das Modell richtet sich an Entwickler und Integratoren, die Code
schreiben und Fehler behandeln können. Wer eine fertige Oberfläche für Mitarbeitende braucht,
nimmt [Copilot als Service](COPILOT_SAAS.md); wer nur Altsysteme verbinden will, nimmt
[Nur Bridge](BRIDGE_ONLY.md).

![Weg eines Dokuments vom Ordner bis ELSTER](../../images/document-path.svg)

*Abbildung 2. Die Schritte 3–5 sind die Embedded API: Eingang, Agent, Ausgabe.*

---

## 2. Was man braucht

| Was | Anforderung | Prüfung |
|---|---|---|
| Basis-URL | Adresse des Copilot oder der Runtime (§3) | `curl -fsS "$BASE_URL/api/v1/license"` |
| API-Token | mit den nötigen Scopes ausgestellt (§4) | `curl ... /api/v1/agents` |
| TLS | gültiges Zertifikat, Prüfung nicht abgeschaltet | `openssl s_client -connect <host>:443 -servername <host>` |
| `curl` | 7.76+ | `curl --version` |
| `jq` | optional | `jq --version` |
| Uhrzeit | NTP, Abweichung unter 5 Minuten | `timedatectl status` |
| Netzwerk | ausgehend HTTPS 443 | `curl -I "$BASE_URL"` |

Die Beispiele sind für `bash`/`zsh` geschrieben. In PowerShell `curl.exe` und
`Invoke-RestMethod` verwenden; die Header bleiben gleich.

---

## 3. Basis-URL

### 3.1 Copilot in der Cloud

```bash
export BASE_URL="https://burokratie.firmspace.de"
export TOKEN='<hier eigenen Token einsetzen>'

curl -fsS "$BASE_URL/actuator/health/readiness"
# {"status":"UP"}

curl -fsS "$BASE_URL/api/v1/license" | jq '{state, plan, expiresAt}'
```

Die API liegt immer unter `<BASE_URL>/api/v1`. In den Beispielen steht der vollständige
Pfad, damit nichts geraten werden muss.

### 3.2 Robots Runtime beim Kunden

```bash
export BASE_URL="http://192.168.1.20:8080"
export TOKEN='<Runtime-Token>'

curl -fsS "$BASE_URL/actuator/health/readiness"
curl -fsS "$BASE_URL/api/v1/license"
```

Die Runtime-Adresse ist die, die der Installer in `API URL:` ausgegeben hat. Ist das Terminal
geschlossen:

```bash
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env
```

Die Runtime-API ist `<diese Adresse>/api/v1`. Mehr in [RUNTIME.md](RUNTIME.md) §3.

### 3.3 Ist die URL richtig?

| Prüfung | Erwartet | Wenn nicht |
|---|---|---|
| `curl -I "$BASE_URL"` | `200`/`302`, kein Timeout | DNS, Proxy, Firewall |
| `curl -fsS "$BASE_URL/actuator/health/readiness"` | `{"status":"UP"}` | API läuft nicht |
| `curl -fsS "$BASE_URL/api/v1/license"` | JSON mit `state` | falscher Pfad oder Proxy schneidet `/api` ab |
| `curl -fsS "$BASE_URL/api/v1/agents"` ohne Token | `401` | `404` — falsche URL |

---

## 4. Woher der Token kommt

### 4.1 Copilot-API-Token

1. Bei `https://burokratie.firmspace.de` anmelden.
2. Kontomenü oben rechts öffnen → **Kontoeinstellungen**.
3. In der Kartenliste **API-Tokens** wählen.
4. **Token anlegen** anklicken.
5. Einen **Namen** vergeben, an dem die Integration erkennbar ist, z. B. `embedded-prod`
   oder `scanner-buchhaltung`. Der Name erscheint in der Liste, der Wert nicht.
6. **Nur die nötigen Rechte** anhaken:
   - `Dokumente einliefern` — das ist `documents:write`, Pflicht für den Datei-Upload;
   - `Dokumente lesen` — `documents:read`, wenn Liste/Inhalt gelesen werden sollen;
   - `Agenten ansehen` — `agents.view`, für Katalog und Protokolle;
   - `Agenten ausführen` — `agents.run`, zum Starten von Agenten und Workflow;
   - `exports:read` — für die ELSTER-Ausgabe; in manchen Versionen steht das technische
     Kürzel als Beschriftung, das ist normal.
7. **Token erstellen** anklicken.
8. Im Dialog **Token angelegt** auf **Token kopieren** klicken und den Wert sofort in den
   Passwortmanager legen. Der Wert wird **einmal** angezeigt; in der Liste bleibt nur der
   Anfang (`prefix`).

`[SCREENSHOT]` Drei Bildschirme: Karte „API-Tokens“, Dialog mit den Rechten, Dialog „Token
angelegt“ mit Kopierknopf.

Ist der Token verloren: **API-Tokens** öffnen, Eintrag an Name und `prefix` erkennen,
**Widerrufen**, neuen anlegen. Der alte Wert ist nicht wiederherstellbar; der Server kennt
nur den SHA-256.

Wichtige Grenzen:

- Rechte lassen sich nach dem Anlegen **nicht ändern**;
- höchstens **20 aktive Token** pro Workspace; beim 21. kommt eine Fehlermeldung;
- standardmäßig **60 Anfragen pro Minute** pro Token (Runtime-Admin stellt 120 aus);
- Token anlegen darf eine Rolle mit `api_tokens.manage` (in der Regel OWNER/ADMIN).

### 4.2 Runtime-Token

Den ersten Token stellt der Installer aus, weitere der Betreiber über die Admin-API:

```bash
set -a; . /opt/firmspace-runtime/runtime.env; set +a
curl -sS -X POST \
  -H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  "http://127.0.0.1:8080/api/v1/runtime/tokens" \
  -d '{"name":"embedded-prod","scopes":["documents:write","agents.view","agents.run","exports:read"]}' | jq
```

Das Feld `token` der Antwort wird einmal angezeigt. Mehr in [RUNTIME.md](RUNTIME.md) §7.

### 4.3 Scopes

| Scope | Öffnet | Öffnet nicht |
|---|---|---|
| `documents:write` | `POST /api/v1/documents:ingest` | fremde Dokumente, Agentenstart |
| `documents:read` | `GET /api/v1/documents`, `/documents/{id}`, `/documents/{id}/content` | Upload und Änderung |
| `agents.view` | `GET /agents`, `/agents/{id}`, `/agents/{id}/runs`, Lesen von Workflow/Konstellationen | Start und Änderung |
| `agents.run` | `POST /agents/{id}/run`, `/constellations/{id}/instantiate`, `/workflows/{id}/run`, `/workflow-runs/{id}/resume` | Veröffentlichen/Bearbeiten von Workflow |
| `exports:read` | `GET /api/v1/exports/elster` | Agentenstart, Einstellungen |
| `settings:read` | Lesen der Einstellungen | Ändern |
| `settings:write` | Ändern von Regeln, Datumsbegriffen, Retention | Token-Verwaltung |

Die Schreibweise `documents.write` (mit Punkt) wird beim Anlegen akzeptiert und zu
`documents:write` normalisiert. Ein unbekannter Scope ist ein Fehler beim Anlegen, kein
stillschweigend gespeichertes Wort.

---

## 5. Die ersten Befehle

### 5.1 Variablen

```bash
export BASE_URL="https://burokratie.firmspace.de"
export TOKEN='<hier eigenen Token einsetzen>'
```

`BASE_URL` ohne abschließenden `/`. Mit `/` am Ende entsteht `//api/v1`, und manche Proxys
antworten mit `404`. Prüfen:

```bash
printf '%s\n' "${BASE_URL%/}"
```

### 5.2 Token prüfen

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/agents"
```

Erwartet `200`. `401` heißt Token falsch oder widerrufen. `403` heißt Token gültig, aber
`agents.view` fehlt; das sind zwei verschiedene Probleme.

### 5.3 Agentenkatalog

```bash
curl -fsS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" | jq '.[0]'
```

Erwartete Antwort (gekürzt):

```json
{
  "id": "doc-detect",
  "name": "Dokument-Erkennung",
  "category": "documents",
  "description": "Erkennt Dokumenttypen und steuert die weitere Verarbeitung an.",
  "functions": ["Typ-Erkennung", "Kanal-Zuordnung", "Übergabe an Fach-Agenten"],
  "triggers": ["document.created", "manual"],
  "implemented": true,
  "runCount": 0
}
```

### 5.4 Datei einliefern

```bash
curl -sS -X POST "$BASE_URL/api/v1/documents:ingest" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: invoice-2026-0004711-1" \
  -H "X-Filename: rechnung-4711.pdf" \
  -H "Content-Type: application/pdf" \
  --data-binary @rechnung-4711.pdf \
  -w '\nHTTP %{http_code}\n'
```

Erwartete Antwort `201`:

```json
{
  "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11",
  "duplicate": false,
  "message": "Dokument aufgenommen",
  "contentHash": "9f2c8a…64 hex…"
}
```

Ein Wiederholungsversuch mit demselben `Idempotency-Key` liefert `200` und dieselbe
`documentId`:

```json
{
  "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11",
  "duplicate": true,
  "message": "Dieser Schlüssel wurde bereits verarbeitet; das vorhandene Dokument wird zurückgegeben",
  "contentHash": "9f2c8a…"
}
```

Bedeutung der `curl`-Parameter:

| Parameter | Zweck |
|---|---|
| `-sS` | kein Fortschrittsbalken, aber Netzwerkfehler anzeigen |
| `-X POST` | Methode |
| `-H "..."` | Header; `Authorization` und `Idempotency-Key` sind Pflicht |
| `--data-binary @datei` | Datei unverändert senden |
| `-o datei` | Antwort in eine Datei schreiben |
| `-w '\nHTTP %{http_code}\n'` | HTTP-Code ausgeben |
| `-f` | HTTP ≥ 400 als Fehler behandeln (für Skripte) |

Regeln für Ingest:

- `Idempotency-Key` ist Pflicht. Ohne ihn `400 INVALID_REQUEST`.
- `X-Filename` ist Pflicht. Ohne ihn `400`.
- Den SHA-256 berechnet der Server; der Client kann ihn nicht vorgeben.
- Dieselbe Datei mit **anderem** Key → `200 duplicate=true`, kein zweites Dokument.
- Derselbe Key, während der erste Aufruf läuft → `409 CONFLICT`.
- Der Key-Eintrag lebt **24 Stunden**.
- `201/200` bedeutet „Datei gespeichert“, nicht „erkannt“. OCR und Kategorien folgen später,
  wenn die Instanz eine Verarbeitungskette hat.

### 5.5 Agenten starten

```bash
curl -sS -X POST "$BASE_URL/api/v1/agents/doc-detect/run" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "trigger": "manual",
        "inputs": {
          "fileName": "rechnung-4711.pdf",
          "documentId": "6f1c2a9e-6d0b-4a4d-9f2e-2a1d7f3b8c11"
        }
      }' | jq
```

Erwartete Antwort:

```json
{
  "runId": "1d9b7c44-2a6e-4f1e-9b0a-7c3d5e8f1a22",
  "agentId": "doc-detect",
  "ok": true,
  "summary": "Dokumenttyp erkannt: Rechnung",
  "produced": [
    { "kind": "document", "ref": "6f1c2a9e-…", "label": "Rechnung" }
  ],
  "meta": {}
}
```

`ok: false` ist eine **reguläre** Agentenantwort (keine Daten, keine Quelle konfiguriert,
Dokument nicht gefunden), kein HTTP-Fehler. Prüfen Sie `ok` und `summary`; einen
Nebeneffekt nicht ohne `produced` und Protokoll als bewiesen ansehen.

`POST /agents/{id}/run` nimmt keinen Idempotenzschlüssel: Jeder Aufruf erzeugt eine neue
`runId`. Gegen Doppelverarbeitung hilft ein Workflow mit `idempotencyKey` oder ein eigener
Schlüssel in `inputs`.

### 5.6 Workflow (wenn ein mehrstufiger Prozess nötig ist)

```bash
# 1. Konstellation als Workflow speichern
curl -sS -X POST "$BASE_URL/api/v1/constellations/datev-pipeline/instantiate" \
  -H "Authorization: Bearer $TOKEN" | jq

# 2. Workflow starten (Idempotenzschlüssel im Body)
curl -sS -X POST "$BASE_URL/api/v1/workflows/<WORKFLOW_ID>/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "triggerType": "API",
        "triggerPayload": {"source": "embedded"},
        "idempotencyKey": "datev-2026-10-27-001"
      }' | jq

# 3. Zustand lesen
curl -fsS "$BASE_URL/api/v1/workflow-runs/<RUN_ID>" \
  -H "Authorization: Bearer $TOKEN" | jq

# 4. Entscheidung bei Pause: APPROVE | RETRY | SKIP
curl -sS -X POST "$BASE_URL/api/v1/workflow-runs/<RUN_ID>/resume" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE"}' | jq
```

Status: `PENDING`, `RUNNING`, `WAITING`, `WAITING_APPROVAL`,
`PAUSED_FOR_HUMAN_REVIEW`, `COMPLETED`, `FAILED`, `CANCELLED`, `COMPENSATED`.
`resume` funktioniert nur bei `PAUSED_FOR_HUMAN_REVIEW`; ein zweiter Aufruf nach der
Entscheidung liefert `409`.

### 5.7 ELSTER-Ausgabe

```bash
# JSON (Standard): Zeilen, Einstellungen, Summen, Warnungen
curl -fsS "$BASE_URL/api/v1/exports/elster?year=2025" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '{year, summary: .summary | {rows, expensesNetto, expensesUst, warnings}}'

# CSV für Excel: Trennzeichen „;“ , Dezimalkomma
curl -fsS "$BASE_URL/api/v1/exports/elster?year=2025&format=csv" \
  -H "Authorization: Bearer $TOKEN" \
  -o elster-euer-2025.csv
```

Erwartetes JSON (gekürzt):

> Die Werte in der Zeile sind ein Beispiel. Die konkrete EÜR-Zeile, Kennzahl und das DATEV-Konto bestimmt der Kategorienkatalog der Instanz.

```json
{
  "year": 2025,
  "settings": {
    "kleinunternehmer": false,
    "sollIst": "SOLL",
    "wirtschaftsjahr": 2025,
    "bewirtungSplit": true
  },
  "summary": {
    "rows": 42,
    "expensesNetto": 12840.55,
    "expensesUst": 2439.70,
    "expensesBrutto": 15280.25,
    "warnings": [
      {
        "code": "GIFT_OVER_50_SINGLE",
        "message": "Geschenk über 50,00 € netto: kein Betriebsausgaben- und kein Vorsteuerabzug (Kz 164).",
        "documentId": "6f1c2a9e-…",
        "details": {}
      }
    ]
  },
  "rows": [
    {
      "categoryCode": "bewirtung",
      "euerLine": "63",
      "euerKz": "175",
      "datevSkr03": "4650",
      "datevSkr04": "6650",
      "bookingKind": "AUSGABE",
      "netto": 100.00,
      "ust": 19.00,
      "brutto": 119.00,
      "vorsteuerAbziehbar": true,
      "documentId": "6f1c2a9e-…",
      "date": "2025-03-14"
    }
  ]
}
```

Die CSV-Spalten sind fest:

```text
category_code;euer_line;euer_kz;datev_skr03;datev_skr04;booking_kind;netto;ust;brutto;vorsteuer_abziehbar;bewirtung_abziehbar_70;bewirtung_nicht_abziehbar_30;document_id;date
```

Regeln:

- `year` ist optional; ohne Angabe gilt das `Wirtschaftsjahr` aus den Einstellungen, sonst
  das laufende Jahr.
- `format` ist `json` oder `csv`; alles andere → `400`.
- Ein leeres Jahr ist `200` mit leerer Liste und Nullen, kein Fehler.
- In CSV gibt es keine Warnungen; sie stehen nur im JSON (`summary.warnings`).
- Das ist keine Steuerberatung: Vor der Abgabe prüft die Buchhaltung die Regeln aus
  [../ELSTER_README.md](../../ELSTER_README.md).

---

## 6. Fehler: Bedeutung und Reaktion

Einheitlicher Fehlerumschlag:

```json
{
  "code": "INVALID_REQUEST",
  "message": "Ohne Dateinamen lässt sich das Dokument später nicht wiederfinden",
  "correlationId": "b1f0…",
  "path": "/api/v1/documents:ingest",
  "timestamp": "2026-10-27T20:00:00Z"
}
```

| HTTP | `code` (Beispiel) | Wann | Was tun |
|---|---|---|---|
| `400` | `INVALID_REQUEST` | `Idempotency-Key`/`X-Filename` fehlt, leerer Body, falsches `year`/`format` | Anfrage korrigieren; nicht wiederholen |
| `401` | `UNAUTHENTICATED` | Token fehlt, unbekannt, widerrufen oder abgelaufen | neuen Token ausstellen; `Bearer` prüfen |
| `402` | `LICENSE_MISSING` | Runtime hat keine gültige Lizenz | Schlüssel einsetzen; Health und `/license` bleiben erreichbar |
| `402` | `LICENSE_READ_ONLY` | Lizenz abgelaufen, Kulanz vorbei | Schlüssel ersetzen; Lesen ja, Schreiben nein |
| `402` | `LICENSE_REVOKED` | Lizenz im Kabinett widerrufen | neuen Schlüssel installieren; keine Kulanz |
| `402` | `LICENSE_RUN_LIMIT` | `maxRunsPerMonth` ausgeschöpft | neuen Monat abwarten oder Lizenz ändern |
| `402` | `SUBSCRIPTION_REQUIRED`, `PLAN_LIMIT_REACHED` | SaaS-Tarif erlaubt es nicht | Tarif verlängern/wechseln |
| `403` | `FORBIDDEN` | Scope fehlt | richtigen Scope vergeben und neuen Token ausstellen |
| `404` | `NOT_FOUND` | Agent/Lauf nicht gefunden; Admin-API verborgen | ID und Basis-URL prüfen |
| `409` | `CONFLICT` | Key wird noch verarbeitet; `resume` nicht in Pause; Bootstrap schon erfolgt | Body lesen, nach Code handeln |
| `413` | `PAYLOAD_TOO_LARGE` | Datei über dem Limit | Datei verkleinern oder Limit bewusst erhöhen |
| `422` | `VALIDATION_FAILED`, `FILE_REJECTED` | Felder ungültig, Datei abgelehnt | Daten korrigieren; nicht wiederholen |
| `429` | `RATE_LIMITED` | Token- oder Gesamtlimit überschritten | Backoff mit Jitter, §7 |
| `502` | `DEPENDENCY_UNAVAILABLE` | Speicher/externe Anlage nicht erreichbar | mit Backoff wiederholen, `correlationId` melden |
| `500` | `INTERNAL_ERROR` | unerwarteter Fehler | mit demselben `Idempotency-Key` wiederholen, `correlationId` melden |

Wichtig zu wissen:

- Auf der Agenten-API kommt `402` **vor** `401`: Ohne Lizenz erhält auch ein Aufruf ohne
  Token `LICENSE_MISSING`, damit der Betreiber die echte Ursache sieht.
- Bei `documents:ingest` blockiert `LICENSE_READ_ONLY` den Eingang, `LICENSE_MISSING`
  dagegen nicht: Dokumente werden bis zum Eintreffen des Schlüssels angenommen.
- `Retry-After` ist beim allgemeinen Limit gesetzt; beim Token-Limit kann der Header fehlen.
  Orientieren Sie sich an `429` und Backoff.
- Der Fehlerbody enthält kein SQL, keinen Stacktrace und keine Daten eines anderen Tenants.

---

## 7. Idempotenz und Limits

### 7.1 Idempotenz

| Operation | Schlüssel | Verhalten |
|---|---|---|
| `documents:ingest` | `Idempotency-Key` + SHA-256 | gleicher Key → gleiche `documentId`, `duplicate=true`; gleicher Inhalt mit anderem Key → `duplicate=true`; Key in Verarbeitung → `409`; Eintrag 24 h |
| `workflows/{id}/run` | `idempotencyKey` im Body | gleicher Key → vorhandene `runId`, `created=false` |
| `workflow-runs/{id}/resume` | keiner | Wiederholung nach Entscheidung → `409`; vorher Status lesen |
| `agents/{id}/run` | keiner | jeder Aufruf erzeugt eine neue `runId` |

Empfohlener Schlüssel:

```text
Idempotency-Key = "<system>:<externe-id>:<versuch>"
```

Der Schlüssel muss für denselben Versuch stabil und für ein neues Dokument anders sein.
Keine zufällige UUID pro Retry verwenden: Das erzeugt zusätzliche Einträge, auch wenn der
Hash ein zweites Dokument verhindert.

### 7.2 Limits

| Limit | Wert | Wo gesetzt |
|---|---|---|
| Anfragen pro Token | 60/min (Copilot), 120/min (Runtime-Admin) | `api_tokens.rate_limit_per_minute` |
| Gesamt pro Benutzer/IP | 600/min pro Instanz | `burokratie.security.rate-limit` |
| Aktive Token | 20 pro Workspace | `MAX_ACTIVE_TOKENS_PER_WORKSPACE` |
| Upload-Größe | 100 MB standardmäßig | `BC_MAX_UPLOAD_SIZE` |
| Anfragegröße | 250 MB standardmäßig | `BC_MAX_REQUEST_SIZE` |
| Idempotenz-Eintrag | 24 h | `idempotency_records.expires_at` |
| Agentenläufe | `maxRunsPerMonth` der Lizenz oder Tarif | Lizenz/Plan |

Empfehlungen:

- Client-Timeout ≥ 60 s für große Dateien;
- bei `429` und `5xx` exponentieller Backoff mit Jitter, 5–8 Versuche;
- bei `400/401/403/404` nicht wiederholen;
- ein Dokument, ein Thread; keine parallelen Ingest-Aufrufe mit verschiedenen Keys.

---

## 8. Sicherheit und Rotation

- Token nur auf dem Server. Nicht im Browser, nicht in einer mobilen App, nicht in der URL,
  nicht in Logs, nicht in Git.
- Getrennte Token für Test und Produktion; ein Token pro Integration.
- Minimale Scopes: Ingest nur `documents:write`; Lesen `documents:read`; Start
  `agents.run`.
- Rotation ohne Ausfall: neuen Token anlegen → ausrollen → prüfen, dass `lastUsedAt` des
  neuen steigt → alten widerrufen.
- Bei Verlust: widerrufen, neuen ausstellen, `agent_runs`/Audit prüfen, Vorfallprozess
  auslösen.
- Der Tenant kommt aus dem Token; ein `tenantId` im Body wird ignoriert.

---

## 9. Integrations-Checkliste

- [ ] Basis-URL geprüft (`/api/v1/license` liefert JSON).
- [ ] Token für den richtigen Workspace, Scopes minimal.
- [ ] `curl /api/v1/agents` mit Token → `200`.
- [ ] `documents:ingest` mit echter Datei → `201` und `documentId`.
- [ ] Wiederholung mit demselben Key → `200` und dieselbe `documentId`.
- [ ] `agents/doc-detect/run` → `ok: true/false`, wird als Geschäftsantwort behandelt.
- [ ] `exports/elster?format=csv` mit `exports:read` → Datei.
- [ ] Fehler `401/402/403/409/429/5xx` nach §6 behandelt.
- [ ] Backoff und Timeouts eingerichtet; kein Retry bei `400/401/403/404`.
- [ ] `correlationId` wird protokolliert.
- [ ] Token-Rotation geprobt.
