# Betrieb und Verwaltung der Lieferungen

**Sprachen:** [Русский](../ru/MANAGEMENT.md) · [Deutsch](MANAGEMENT.md) · [English](../en/MANAGEMENT.md)

**Für wen:** Vertragsinhaber, IT-Administrator des Kunden, Einführungsingenieur und Support.
Das Dokument beschreibt die **gemeinsamen** Abläufe aller vier Modelle: Lizenzen, Token,
Updates, Sicherungen, Protokolle und Vorfälle. Die Installation steht in
[BRIDGE_ONLY.md](BRIDGE_ONLY.md), [RUNTIME.md](RUNTIME.md),
[EMBEDDED_API.md](EMBEDDED_API.md) und [COPILOT_SAAS.md](COPILOT_SAAS.md).

**Grundsatz:** Jedes Geheimnis, jedes Protokoll und jede Verpflichtung hat einen
**Verantwortlichen**. Ohne Verantwortlichen ist es keine Operation, sondern eine Hoffnung.

---

## 1. Worum es geht und für wen es gedacht ist

Dieses Dokument richtet sich an alle, die nach der Abnahme für eine laufende Lieferung
zuständig sind: Vertragsinhaber, Kundenadministrator und Support. Es erklärt, wer wofür
haftet, wie eine Lizenz ausgestellt und widerrufen wird, wie jede Komponente aktualisiert
wird, wo Sicherungen und Protokolle liegen und was in den ersten Minuten eines Vorfalls zu
tun ist. Installationsanleitungen stehen in den Modelldokumenten. Alle Befehle sind gegen
Skripte und Code geprüft; Stellen, die eine laufende Oberfläche brauchen, sind mit
`[SCREENSHOT]` markiert.

---

## 2. Was man braucht

### 2.1 Zugänge und Artefakte

| Was | Zweck | Wo |
|---|---|---|
| `infra/runtime.env` bzw. `<dir>/runtime.env` | Datenbankpasswort, AES-Schlüssel, Admin-Token, Lizenz | Safe/Passwortmanager, Kopie in der Sicherung |
| `bridge.config.json` + `.env` | Token und Einstellungen der Bridge | Bridge-Rechner, Datei `600` |
| `LICENSE_KEY`, `LICENSE_SECRET` | Runtime-Lizenz | Safe |
| `instance-id` | Bindung der Lizenz | `runtime-data/instance-id` |
| API-Token | Integrationen | Secret Store des Kunden |
| Zugang zum Inhaber-Kabinett | Lizenzen, Unternehmen, Bridges | beim Inhaber |
| Supportkontakte und SLA | Eskalation | Vertrag |

### 2.2 Werkzeuge

| Werkzeug | Version | Prüfung |
|---|---|---|
| `docker` | 24+ | `docker version` |
| `docker compose` | v2 | `docker compose version` |
| `curl` | 7.76+ | `curl --version` |
| `jq` | beliebig | `jq --version` |
| `pg_dump`/`pg_restore` | 17 (im Container) | `docker compose ... exec postgres pg_dump --version` |
| `openssl` | beliebig | `openssl version` |

---

## 3. Wer wofür zuständig ist

| Aufgabe | Inhaber (Firmspace) | Kunde | Einführung |
|---|---|---|---|
| Lizenz ausstellen/widerrufen | **R** | C | I |
| `LICENSE_SECRET` und `runtime.env` aufbewahren | C | **R** | C |
| Runtime installieren | C | A | **R** |
| Server, Speicher, Netzwerk, OS | I | **R** | C |
| Runtime aktualisieren | C | A | **R** |
| Sicherungen und Wiederherstellung prüfen | I | **R** | C |
| API-Token ausstellen | I | **R** | C |
| Bridge installieren/aktualisieren | C | A | **R** |
| Bridge: Ordner, Post, Datenbank | I | **R** | C |
| Monitoring und erste Linie | I | **R** | C |
| Sicherheitsvorfall | **R** | **R** | C |
| Cloud-Kabinett Copilot | **R** | C | I |

`R` — führt aus, `A` — genehmigt, `C` — berät, `I` — wird informiert. Zwei `R` sind möglich,
wenn die Zuständigkeit nach Umgebung geteilt ist (z. B. Sicherheitsvorfall).

---

## 4. Lizenzen

### 4.1 Lizenzmodell

Die Runtime-Lizenz ist ein HMAC-signierter Schlüssel, gebunden an die `instance-id`. Die
Signatur wird offline geprüft; zusätzlich kann die Installation das Kabinett zum Widerruf
befragen (`LICENSE_CHECK_URL`, alle 6 Stunden). Kaufmännisch ist die Lizenz **standardmäßig
unbefristet**: keine jährliche Verlängerung. Technisch ist das ein Schlüssel **ohne
`expiresAt`**: immer `LICENSE_ACTIVE`, ohne Kulanzfrist. Eine befristete Lizenz enthält
`expiresAt` und durchläuft Kulanz/Read-only.

`LICENSE_SECRET` ist das gemeinsame Signaturgeheimnis. Es muss bei Inhaber und Kunde
übereinstimmen, sonst schlägt die Prüfung fehl. Es gehört nicht in die Bridge, nicht auf die
Seite `/runtime-admin` und nicht in Git.

### 4.2 Lizenz ausstellen

1. Inhaber-Kabinett öffnen (für Lizenzen und Bridges:
   `https://agents.firmspace.de`).
2. **Lizenzen** öffnen.
3. **Ausstellen** anklicken.
4. Ausfüllen:
   - `customer` — juristischer Name des Kunden;
   - `instanceId` — Wert aus `runtime-data/instance-id` (der Kunde sendet ihn bei der
     Installation);
   - `plan` — z. B. `ENTERPRISE`;
   - `maxAgents`, `maxRunsPerMonth` — Limits;
   - `features` — falls nötig.
5. `LICENSE_KEY` kopieren und zusammen mit `LICENSE_SECRET` über einen sicheren Kanal
   übergeben.

`[SCREENSHOT]` Formular zum Ausstellen: customer, instanceId, plan, Limits, features.
Die Standardlizenz ist unbefristet; ein Laufzeitfeld kann in der Form fehlen. Wurde ausnahmsweise eine Laufzeit vereinbart, stellt Firmspace den Schlüssel mit `expiresAt` aus.

Der Kunde installiert den Schlüssel über `/runtime-admin` (**Lizenz → Installieren**) oder
über `runtime.env` ([RUNTIME.md](RUNTIME.md) §5.3). Der Web-Weg braucht keinen Neustart und
schreibt `/app/data/license.key`. Prüfung:

```bash
curl -fsS "http://127.0.0.1:8080/api/v1/license" \
  | jq '{state, perpetual, customer, expiresAt, daysRemaining, revokedAt}'
```

Erwartet: `state: "LICENSE_ACTIVE"`. Bei unbefristet: `perpetual: true`, `expiresAt: null`.

### 4.3 Lizenz widerrufen

1. Inhaber-Kabinett → **Lizenzen**.
2. Eintrag über `licenseId` oder `instanceId` suchen.
3. **Widerrufen** anklicken, Grund angeben, bestätigen.

`[SCREENSHOT]` Lizenzliste und Widerrufsdialog.

**Was technisch passiert.** Die Installation fragt etwa eine Minute nach dem Start und danach alle 6 Stunden den
öffentlichen Lizenzstatus über `LICENSE_CHECK_URL` ab und wechselt bei `active:false` in
`LICENSE_REVOKED`: Agenten, Workflow, Exporte und Einstellungen antworten `402`, ohne
Kulanz. Der Dokumenteingang bleibt offen. Die letzte Antwort wird in
`/app/data/license-state.json` zwischengespeichert und überlebt einen Neustart; eine
Kabinett-Störung behält den letzten bekannten Zustand.

**Grenze der aktuellen Lieferung.** Die Compose-Datei übergibt `LICENSE_CHECK_URL` nicht
standardmäßig an den Container. Prüfen:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_CHECK_URL
```

Leere Ausgabe — ein Widerruf im Kabinett erreicht die Installation nicht. Die Variable in
die Umgebung des `api`-Dienstes geben (z. B.
`https://agents.firmspace.de/api/license/status`), `api` neu starten und
`revocationCheckedAt` in `/api/v1/license` prüfen. Keinen sofortigen Widerruf versprechen:
die Verzögerung beträgt bis zu 6 Stunden.

### 4.4 Laufzeit und Zustände prüfen

| Zustand | Bedeutung | Maßnahme |
|---|---|---|
| `LICENSE_ACTIVE` | Schlüssel gültig; bei unbefristet immer | planmäßiger Betrieb |
| `LICENSE_GRACE` | Laufzeit abgelaufen, 14 Tage Kulanz (nur mit `expiresAt`) | Schlüssel ersetzen |
| `LICENSE_READ_ONLY` | Kulanz vorbei | Schlüssel ersetzen; Schreiben und Läufe blockiert |
| `LICENSE_REVOKED` | Kabinett hat die Lizenz widerrufen | neuen Schlüssel; keine Kulanz |
| `LICENSE_MISSING` | kein/ungültiger Schlüssel | `reason` prüfen, Schlüssel einsetzen |

Header der Agenten-API: `X-License-Status`, `X-License-Plan`, `X-License-Expires-At`,
`X-License-Warning`. Sie eignen sich für das Monitoring.

### 4.5 Lizenzinventar

Führen Sie eine Tabelle: `licenseId`, `customer`, `instanceId`, `plan`, `expiresAt`
(leer bei unbefristet), Ausstellungsdatum, ausgestellt von, Widerrufsdatum, Grund. Ohne `instanceId` lässt sich nicht
belegen, dass der Schlüssel für diese Installation bestimmt war.

---


![Woher der Token kommt](../../images/token-locations.svg)

*Abbildung 3. Drei verschiedene Token: Copilot-API, Bridge, Runtime-Admin. Sie sind nicht austauschbar.*

## 5. Token und Geheimnisse

### 5.1 Inventar

| Geheimnis | Modell | Wo | Wer stellt aus | Rotation | Widerruf |
|---|---|---|---|---|---|
| `BRIDGE_TOKEN` | Bridge | `.env` 600 | Roboter-Kabinett | bei Verlust/Wechsel | Kabinett → Bridge → Widerrufen |
| Copilot-API-Token | SaaS/Embedded | Secret Store | Kabinett → API-Tokens | 90 Tage oder bei Verlust | `POST /api/v1/api-tokens/{id}/revoke` |
| Runtime-API-Token | Runtime | Secret Store | Admin-API/Installer | 90 Tage oder bei Verlust | `POST /api/v1/runtime/tokens/{id}/revoke` |
| `BC_RUNTIME_ADMIN_TOKEN` | Runtime | `runtime.env` 600 | Installer | bei Betreiberwechsel/Verlust | env ändern + Neustart |
| `LICENSE_KEY`/`LICENSE_SECRET` | Runtime | Safe + env | Inhaber | bei Neuausstellung | Schlüssel ersetzen |
| `LICENSE_CHECK_URL` | Runtime | `runtime.env`/Compose | Firmspace | bei Kabinettwechsel | env ändern + Neustart |
| `/app/data/license.key`, `/app/data/license-state.json` | Runtime | Datenverzeichnis 600 | `/runtime-admin` | — | löschen + neuer Schlüssel |
| `BC_ENCRYPTION_KEY` | Runtime | env/Safe | Kunde/wir | **nicht im laufenden Betrieb** | Neuverschlüsselung |
| Datenbankpasswort | Runtime | `runtime.env` | Installer | nach Richtlinie | Neuaufbau |

### 5.2 Regeln

- Ein Token pro Integration oder Bridge. Ein Sammel-Token lässt sich nicht gezielt
  widerrufen.
- Der Token wird einmal angezeigt; gespeichert ist nur der SHA-256.
- Keine Token in `bridge.config.json`, Git, Chats, Tickets oder Screenshots.
- Rotation ohne Ausfall: neu → ausrollen → `lastUsedAt` prüfen → alt widerrufen.
- Bei Verlust: innerhalb von 15 Minuten widerrufen, Protokolle prüfen, nach §9 melden.

### 5.3 Ausstellen und widerrufen

**Copilot-API-Token:** Kabinett → Kontoeinstellungen → API-Tokens → Token anlegen.
Widerruf über **Widerrufen** in der Liste.

**Runtime-API-Token:**

```bash
set -a; . /opt/firmspace-runtime/runtime.env; set +a
ADMIN=(-H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN")
BASE="http://127.0.0.1:8080"

curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/tokens" | jq
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"integration-x","scopes":["documents:write"]}' | jq
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "${ADMIN[@]}" \
  "$BASE/api/v1/runtime/tokens/<TOKEN_ID>/revoke"
```

**Bridge-Token:** Roboter-Kabinett → Unternehmen → Unternehmen → Verbindungen → Bridge →
Widerrufen. Danach erhält die Bridge `401`; neu anlegen und `.env` aktualisieren.

### 5.4 Runtime-Admin-Token

- Wird vom Installer erzeugt (`openssl rand -hex 32`) und in `runtime.env` geschrieben.
- Wird **nicht** im Terminal ausgegeben. Anzeigen:

  ```bash
  sudo grep '^BC_RUNTIME_ADMIN_TOKEN=' /opt/firmspace-runtime/runtime.env | cut -d= -f2-
  ```

- Nur im Header `X-Runtime-Admin-Token` übertragen.
- Ist der Wert leer, antworten die Admin-Endpunkte mit `404` (Oberfläche verborgen).
- Wechsel: `openssl rand -hex 32` → in `runtime.env` ersetzen → `api` neu starten →
  `GET /api/v1/runtime/status` prüfen. Der alte Token ist sofort ungültig.
- Nicht an Bridge oder Kunden-Software weitergeben: Es ist ein Betreiber-Schlüssel.

---

## 6. Updates

### 6.1 Copilot SaaS

Firmspace aktualisiert. Der Kunde muss nichts tun; Änderungen kommen mit dem Cloud-Update.
Hat sich die Oberfläche geändert, prüfen Sie die Abläufe aus [COPILOT_SAAS.md](COPILOT_SAAS.md)
und aktualisieren Sie interne Anleitungen.

### 6.2 Robots Runtime

```bash
cd /pfad/zu/burokratie-copilot
git pull                          # oder neues Lieferarchiv entpacken
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml build
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Regeln:

- Vor dem Update Datenbank und `runtime-data` sichern.
- Flyway arbeitet forward-only; Schema-Rollback nur über Wiederherstellung aus dem Dump.
- `LICENSE_KEY` und `instance-id` liegen außerhalb des Images und bleiben erhalten.
- `BC_ENCRYPTION_KEY` nicht zusammen mit dem Update ändern.
- Fenster: Die API ist während des Neustarts nicht erreichbar (Sekunden bis Minuten). Für HA
  ist ein zweiter Knoten nötig; das gehört nicht zur Basislieferung.
- Im Ticket Image/Digest, Git-Revision, Datum und Betreiber festhalten.

Rollback:

```bash
BC_API_IMAGE='firmspace/robots-runtime-api:<previous>' \
  docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d --force-recreate api
```

Ist die Migration inkompatibel, die Datenbank aus der Sicherung wiederherstellen und das
vorige Image starten.

### 6.3 Firmspace Bridge

```bash
docker load -i firmspace-bridge-<neue-Version>-<Architektur>.tar.gz
docker stop firmspace-bridge && docker rm firmspace-bridge
docker run -d --name firmspace-bridge --restart unless-stopped \
  -v /opt/firmspace-bridge/config:/data \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  --env-file /opt/firmspace-bridge/config/.env \
  firmspace-bridge:<neue-Version>
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

Vor dem Update die Versionshinweise lesen: Das Konfigurationsformat kann sich ändern. Das
Volume `.bridge` nicht löschen — dort liegen Warteschlange und Protokoll.

---

## 7. Sicherungen

### 7.1 Was und wie oft

| Was | Warum | Häufigkeit | Aufbewahrung |
|---|---|---|---|
| PostgreSQL Runtime | Tenant, Dokumente, Läufe, Audit | täglich + vor Updates | mindestens 30 Tage, laut Vertrag |
| `runtime-data` (Blobs, instance-id) | Dateien und Lizenzbindung | täglich | wie Datenbank |
| `runtime.env` | Datenbankpasswort, AES-Schlüssel, Admin-Token, Lizenz | bei Änderung | Safe, getrennt von der Datenbank |
| Bridge-Konfiguration und `.env` | Wiederaufbau der Bridge | bei Änderung | Secret Store des Kunden |
| TLS-Zertifikate und Proxy-Konfiguration | HTTPS | bei Änderung | Safe |

### 7.2 Befehle für die Runtime

```bash
cd /pfad/zu/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
COMPOSE=(docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml)

BACKUP=/srv/backups/firmspace-runtime/$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$BACKUP"

"${COMPOSE[@]}" exec -T postgres pg_dump -U "$BC_DB_USER" -d "$BC_DB_NAME" -Fc \
  > "$BACKUP/database.dump"

sudo tar czf "$BACKUP/runtime-data.tgz" \
  -C "$(dirname "$BC_DATA_DIR")" "$(basename "$BC_DATA_DIR")"

sudo install -m 600 /opt/firmspace-runtime/runtime.env "$BACKUP/runtime.env"
sha256sum "$BACKUP"/* > "$BACKUP/SHA256SUMS"
```

Dump ohne Wiederherstellung prüfen:

```bash
docker run --rm -v "$BACKUP:/backup:ro" postgres:17 \
  pg_restore --list /backup/database.dump | head
```

### 7.3 Wiederherstellung

```bash
"${COMPOSE[@]}" stop api
"${COMPOSE[@]}" exec -T postgres pg_restore -U "$BC_DB_USER" -d "$BC_DB_NAME" \
  --clean --if-exists --no-owner < "$BACKUP/database.dump"
sudo tar xzf "$BACKUP/runtime-data.tgz" -C "$(dirname "$BC_DATA_DIR")"
"${COMPOSE[@]}" up -d api
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Einmal pro Quartal eine **Übungswiederherstellung** auf einem separaten Stand durchführen.
Eine Sicherung, die nie zurückgespielt wurde, ist eine Hoffnung, keine Sicherung.

### 7.4 SaaS

Sicherungen macht Firmspace; der Kunde ist für den Export seiner Daten vor Vertragsende
verantwortlich. Dokument- und ELSTER-Export über UI/API, nicht über SQL. Aufbewahrung nach
Vertragsende richtet sich nach Vertrag und Retention-Einstellung.

---

## 8. Protokolle und Monitoring

### 8.1 Was beobachten

| Quelle | Was | Befehl/Ort |
|---|---|---|
| Health Runtime | `UP`/`DOWN` | `curl -fsS .../actuator/health/readiness` |
| Lizenz | Zustand, Laufzeit | `curl -fsS .../api/v1/license` |
| Agenten-Header | `X-License-Status` | jede Antwort von `/api/v1/agents` |
| API-Logs | Fehler, Start, Migrationen | `docker compose ... logs -f api` |
| PostgreSQL-Logs | Fehler, Speicher | `docker compose ... logs postgres` |
| Metriken | `metrics`, `prometheus` (falls aktiv) | `/actuator/metrics` |
| Speicher | freier Platz | `df -h`, Alarm < 20 % |
| Bridge | Heartbeat, Warteschlange | Kabinett + `.bridge/journal` |
| Kabinett-Audit | Anmeldungen, Änderungen | Audit-Oberfläche |
| SaaS | Erreichbarkeit | externes Monitoring `https://...` |

### 8.2 Alarme (Minimum)

- `health/readiness` länger als 2 Minuten nicht `UP`;
- `/api/v1/license` nicht `LICENSE_ACTIVE` (außer vereinbarter Kulanz) oder `LICENSE_REVOKED`;
- Datenbankspeicher < 20 %;
- keine Sicherung in den letzten 24 Stunden;
- Bridge länger als 10 Minuten ohne Verbindung;
- Anstieg von `401/403/429` auf der API.

---

## 9. Vorfälle

### 9.1 Die ersten 15 Minuten

1. **Zeit, Symptom und betroffene Komponenten** festhalten.
2. **Nicht alles neu starten**: zuerst `docker logs`, `ps`, `df -h` und die letzten 200
   Logzeilen sichern.
3. **Schaden begrenzen:** verdächtigen Token widerrufen, unnötige Ports schließen, bei
   Verlust das Geheimnis ersetzen.
4. **Informieren:** Inhaber und Support über die Kontakte aus dem Vertrag.
5. **Notieren:** `correlationId` aus der API-Antwort und `documentId`/`runId`, wenn ein
   konkretes Objekt betroffen ist.

### 9.2 Typische Szenarien

| Symptom | Prüfen | Maßnahme |
|---|---|---|
| API antwortet nicht | `docker compose ps`, `logs api`, `df -h` | Container starten, Speicher freigeben, aus Sicherung wiederherstellen |
| Alle Agenten `402` | `/api/v1/license`, `reason` | Schlüssel einsetzen/ersetzen; bei `LICENSE_REVOKED` neuen Schlüssel |
| Token funktioniert nicht mehr | `401` vs. `403` | `401` — widerrufen/abgelaufen; `403` — Scope fehlt |
| Bridge ohne Verbindung | `bridge doctor`, TLS, DNS | siehe [BRIDGE_ONLY.md](BRIDGE_ONLY.md) §7 |
| Dokumente erscheinen nicht | Warteschlange, Ingest, OCR | `documentId`, Logs, Verarbeitungsstatus prüfen |
| Verdacht auf Token-Leck | Protokolle, `lastUsedAt` | widerrufen, neu ausstellen, Zugriffe prüfen |

### 9.3 Datenleck

1. Sofort alle Token widerrufen und `BC_RUNTIME_ADMIN_TOKEN` ändern.
2. Logs und Beweise sichern, Protokolle nicht „aufräumen“.
3. DPO/Inhaber informieren; Fristen und Ablauf nach
   [../compliance/breach-plan.md](../../compliance/breach-plan.md).
4. Betroffene Dokumente/Tenants feststellen.
5. Nach der Behebung: Nachbereitung und Eintrag im Vorfallregister.

### 9.4 Kontakte

Supportkontakte, Zeiten, SLA und Eskalation sind **nicht im Code** hinterlegt und gehören in
den Vertrag. Tabelle vor der Abnahme füllen:

| Stufe | Wer | Kanal | Reaktionszeit |
|---|---|---|---|
| Erste Linie | `<aus dem Vertrag>` | `<Telefon/E-Mail>` | `<SLA>` |
| Technik | `<aus dem Vertrag>` | `<Kanal>` | `<SLA>` |
| Sicherheit/DPO | `<aus dem Vertrag>` | `<Kanal>` | `<SLA>` |

Diese Werte nicht für den Kunden erfinden.

---

## 10. Außerbetriebnahme

1. Nutzer informieren und Termin abstimmen.
2. Dokumente und ELSTER-Daten exportieren (UI/API).
3. Alle API- und Bridge-Token widerrufen.
4. Bridges abschalten, `.env` und Konfiguration löschen.
5. Runtime stoppen, letzte Sicherung erstellen, Daten nach Vertrag löschen.
6. Löschung schriftlich bestätigen.

---

## 11. Betriebs-Checkliste

- [ ] Inventar für Lizenzen, Token und Geheimnisse gefüllt.
- [ ] `runtime.env`/`.env` mit Rechten `600`, Kopie im Safe.
- [ ] Sicherungen eingerichtet und Wiederherstellung geprüft.
- [ ] Monitoring für Health/Lizenz/Speicher/Sicherung aktiv; Alarm auf `LICENSE_REVOKED`.
- [ ] Entscheidung zu `LICENSE_CHECK_URL` dokumentiert (Widerrufsprüfung an oder aus).
- [ ] Token-Rotation geplant.
- [ ] Kontakte und SLA im Vertrag festgehalten.
- [ ] Update- und Rollback-Ablauf auf einem Stand geprüft.
- [ ] Verantwortliche nach RACI benannt.
