# Robots Runtime (On-Premises)

**Sprachen:** [Русский](../ru/RUNTIME.md) · [Deutsch](RUNTIME.md) · [English](../en/RUNTIME.md)

**Modell:** Die Agenten-Engine von Firmspace läuft auf dem Server des Kunden. Nach außen
gibt es nur eine API; ein Web-Kabinett gehört nicht zur Lieferung. Dokumente, Metadaten und
Protokolle bleiben im Kundenbereich, sofern der Kunde nicht selbst externe KI, E-Mail oder
einen anderen ausgehenden Kanal einschaltet.

![Lizenzfluss der Runtime](../../images/runtime-license.svg)

*Abbildung 4. Der Schlüssel wird unter `/runtime-admin` eingefügt, in
`/app/data/license.key` gespeichert und sofort geprüft. Ein Widerruf kommt nicht sofort,
sondern bei der periodischen Prüfung aus dem Kabinett.*

---

## 1. Worum es geht und für wen es gedacht ist

Robots Runtime ist eine API-Anwendung (Spring Boot, Profil `runtime`) zusammen mit
PostgreSQL 17, die auf dem Server des Kunden in Docker läuft. Enthalten sind Agenten,
Workflow, Dokumenteingang, OCR, Exporte, Lizenz und eine administrative API. Eine
Weboberfläche gibt es nicht: Die Verwaltung läuft über HTTP, den ersten Token erzeugt der
Installer. Das Modell passt zu Kunden, die Dokumente nicht in die Cloud geben dürfen, aber
Server, PostgreSQL und Sicherungen betreiben können. Wer ein fertiges Kabinett ohne eigene
Infrastruktur braucht, nimmt [Copilot als Service](COPILOT_SAAS.md).

---

## 2. Was man braucht

### 2.1 Server

| Profil | CPU | RAM | Speicher | Wann |
|---|---|---|---|---|
| Klein (≤ 20 API-Clients, ≤ 50 000 Dokumente) | 4 vCPU | 8 GB | 200 GB SSD | Pilot, kleine Kanzlei |
| Mittel (≤ 200 Clients, ≤ 500 000 Dokumente) | 8 vCPU | 16 GB | 1 TB SSD | Produktion |
| Groß | 16 vCPU | 32 GB | nach Volumen + separates Sicherungsmedium | viele parallele Läufe |

Das sind Startwerte, keine Garantien. Der tatsächliche Bedarf hängt von OCR, Dateigröße,
parallelen Läufen und Aufbewahrungsdauer ab.

### 2.2 Software

| Komponente | Anforderung | Prüfung |
|---|---|---|
| Betriebssystem | Linux x86_64/arm64 | `uname -a` |
| Docker Engine | 24 oder neuer | `docker version --format '{{.Server.Version}}'` |
| Docker Compose | v2 | `docker compose version` |
| `curl` | 7.76+ | `curl --version` |
| `git` | für die Quelldateien der Lieferung | `git --version` |
| `openssl` | zum Erzeugen von Geheimnissen | `openssl version` |
| PostgreSQL | 17, im Compose enthalten | `docker compose ... exec postgres psql --version` |
| TLS-Proxy | nginx/Caddy, Zertifikat für die Domain | `openssl s_client -connect <host>:443` |
| NTP | Abweichung unter 5 Minuten | `timedatectl status` |

### 2.3 Netzwerk und Ports

| Richtung | Port | Zweck |
|---|---|---|
| Client → Proxy | 443/tcp | API über HTTPS |
| Proxy → api | 8080/tcp (intern) | Weiterleitung |
| api → postgres | 5432/tcp (intern) | Datenbank |
| api → S3 (falls genutzt) | 443/tcp | Dokumentdateien |
| Monitoring → api | 8080 (intern) | `/actuator/health/readiness` |

PostgreSQL und der API-Port dürfen nicht nach außen veröffentlicht werden. Mit `local-fs`
ist für den Betrieb kein ausgehendes Internet nötig; es wird für Updates und Support
gebraucht.

### 2.4 Vor der Installation vorbereiten

| # | Was | Wer | Zweck |
|---|---|---|---|
| 1 | Domain und TLS-Zertifikat oder interne IP | Kunde | `BC_PUBLIC_BASE_URL` |
| 2 | `LICENSE_KEY` und `LICENSE_SECRET` (≥ 32 Zeichen) | Firmspace | Lizenz |
| 3 | Server mit Docker 24+/Compose v2 | Kunde | Runtime-Stack |
| 4 | Datenverzeichnis, z. B. `/opt/firmspace-runtime/data` | Kunde | `instance-id`, Dateien |
| 5 | Entscheidung zu Sicherungen | Kunde | PostgreSQL + Datenverzeichnis |
| 6 | Entscheidung zu `LICENSE_CHECK_URL` (Widerrufsprüfung) | Firmspace + Kunde | Fernwiderruf einschalten oder bewusst aus lassen |

> `LICENSE_SECRET` ist kein Kundenpasswort, sondern das gemeinsame HMAC-Geheimnis, mit dem
> der Schlüssel signiert ist. Ohne ihn kann die Runtime den Schlüssel nicht prüfen und
> bleibt in `LICENSE_MISSING`.

---

## 3. Basis-URL: wo sie steht

Die Runtime hat keine feste „Cloud-Adresse“. Die Basis-URL ist die Adresse, die dem
Installer in `--public-url` übergeben wurde; sie erscheint am Ende der Installation in der
Zeile `API URL:`.

Ist das Terminal geschlossen, steht die Adresse in der Umgebungsdatei:

```bash
# bei Installation mit --dir /opt/firmspace-runtime
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env

# ohne --dir
sudo grep '^BC_PUBLIC_BASE_URL=' /pfad/zu/burokratie-copilot/infra/runtime.env
```

Beispielausgabe:

```text
BC_PUBLIC_BASE_URL=http://192.168.1.20:8080
```

Dann gilt:

| Was | Adresse |
|---|---|
| Basis-URL | `http://192.168.1.20:8080` |
| API | `http://192.168.1.20:8080/api/v1` |
| Health | `http://192.168.1.20:8080/actuator/health/readiness` |
| Lizenz | `http://192.168.1.20:8080/api/v1/license` |
| Admin-API | `http://192.168.1.20:8080/api/v1/runtime` |

Prüfung:

```bash
export BASE_URL="http://192.168.1.20:8080"
curl -fsS "$BASE_URL/actuator/health/readiness"
# {"status":"UP"}

curl -fsS "$BASE_URL/api/v1/license"
```

Eine zweite Quelle ist die Bootstrap-Antwort bei der Installation (Feld `apiBase`), eine
dritte die Zeile `API URL:` des Installers. Andere Orte gibt es nicht; die Adresse lässt
sich nicht aus einem Hostnamen erraten.

---

## 4. Installation

### 4.1 Lieferverzeichnis vorbereiten

```bash
cd /pfad/zu/burokratie-copilot
ls scripts/install-runtime.sh scripts/bootstrap-runtime.sh infra/docker-compose.runtime.yml
```

Alle drei Dateien müssen existieren. Der Installer baut das Image aus diesem Checkout; das
Verzeichnis muss daher auch nach der Installation bleiben, weil Compose-Datei und
Betriebsskripte darin liegen.

### 4.2 Ein Befehl

```bash
sudo scripts/install-runtime.sh \
  --public-url http://192.168.1.20:8080 \
  --bind-address 0.0.0.0 \
  --api-port 8080 \
  --dir /opt/firmspace-runtime \
  --tenant "Muster GmbH" \
  --email admin@example.de \
  --owner-name "Admin Muster" \
  --license-key-file /root/firmspace/license.key \
  --license-secret-file /root/firmspace/license.secret \
  --non-interactive
```

Was der Befehl der Reihe nach tut:

1. Prüft Docker, Compose, `curl` und die Compose-Datei.
2. Erzeugt Datenbankpasswort, AES-Schlüssel, Runtime-Admin-Token und `instance-id`.
3. Schreibt `/opt/firmspace-runtime/runtime.env` mit Rechten `600`.
4. Baut das API-Image und startet `postgres` + `api`.
5. Ruft `scripts/bootstrap-runtime.sh` auf: legt Tenant, Workspace, Owner und den ersten
   API-Token an und wartet auf Health.
6. Gibt Adresse, IDs, Lizenzzustand und **den Token einmalig** aus.

Parameter des Installers (gegen die Argumentauswertung geprüft):

| Parameter | Bedeutung | Standard |
|---|---|---|
| `--public-url` | externe API-Adresse | `http://localhost:8080` oder LAN-IP |
| `--domain` | Kurzform: `--public-url https://<domain>` | — |
| `--bind-address` | Adresse, auf der die API lauscht | `127.0.0.1` |
| `--lan` / `--localhost` | schnell `0.0.0.0` / `127.0.0.1` | `127.0.0.1` |
| `--api-port` / `--port` | API-Port | `8080` |
| `--dir` | Installationswurzel: `<dir>/runtime.env` + `<dir>/runtime-data` | — |
| `--data-dir` | Datenverzeichnis ohne `--dir` | `infra/runtime-data` |
| `--tenant` | Name des Tenants | `Firmspace Robots Runtime` |
| `--email` | E-Mail des Inhabers; in `--non-interactive` Pflicht | — |
| `--owner-name` | Name des Inhabers | `Runtime Owner` |
| `--license-key-file` | Datei mit dem Lizenzschlüssel | — |
| `--license-secret-file` | Datei mit dem HMAC-Geheimnis (≥ 32 Zeichen) | — |
| `--admin-token-file` | vorhandener Admin-Token statt Neuerzeugung | — |
| `--env-file` | Pfad zur Umgebungsdatei | `infra/runtime.env` |
| `--project-name` | Name des Compose-Projekts | `firmspace-robots-runtime` |
| `--no-build` | Image nicht bauen | — |
| `--force` | Umgebungsdatei auf einer **frischen** Installation überschreiben | — |
| `--non-interactive` | ohne Rückfragen | — |

### 4.3 Interaktiver Ablauf

Ohne Parameter fragt der Installer:

```text
Public API URL [http://localhost:8080]:
Bind API to [127.0.0.1] (127.0.0.1 = local only, 0.0.0.0 = LAN):
Data directory [/pfad/zu/burokratie-copilot/infra/runtime-data]:
Tenant name [Firmspace Robots Runtime]:
Owner e-mail (for audit/token ownership):
Owner display name [Runtime Owner]:
Licence key (empty = start in LICENSE_MISSING; agents answer 402):
Licence secret (the shared HMAC secret, min 32 chars):
```

Ohne Schlüssel bleibt das Feld leer: Die Installation startet in `LICENSE_MISSING`, Agenten
antworten `402`, aber Health, `/api/v1/license`, Admin-API und Ingest funktionieren. Der
Schlüssel kann später ergänzt werden (§5.3).

### 4.4 Was erscheinen muss

Am Ende steht ein Block wie dieser:

```text
Firmspace Robots Runtime — installation ready
=============================================

API URL:       http://192.168.1.20:8080
Local health:  http://127.0.0.1:8080/actuator/health/readiness
Tenant:        <uuid>
Workspace:     <uuid>
Instance id:   <uuid>
Licence:       LICENSE_ACTIVE (plan ENTERPRISE, Muster GmbH)
Licence ends:  perpetual (no expiry)
Token id:      <uuid> (prefix bc_…)
Token:         <wird einmalig angezeigt>

This token is shown ONCE. The server stores only its SHA-256 hash.
```

Den `Token` sofort in den Passwortmanager legen. Wurde das Terminal geschlossen, lässt sich
über `scripts/bootstrap-runtime.sh` oder die Admin-API (§7) ein neuer Token ausstellen; ein
zweiter Tenant entsteht dabei nicht.

Ohne Lizenz erscheint eine Warnung:

```text
WARNING: no valid licence is installed: agent endpoints answer 402 until LICENSE_KEY is set
```

### 4.5 Prüfung nach der Installation

```bash
cd /pfad/zu/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
COMPOSE=(docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml)

"${COMPOSE[@]}" ps
curl -fsS "http://127.0.0.1:${BC_API_PORT:-8080}/actuator/health/readiness"
curl -fsS "http://127.0.0.1:${BC_API_PORT:-8080}/api/v1/license"
"${COMPOSE[@]}" logs --tail=100 api
```

Erwartet: beide Container `Up`, Health `{"status":"UP"}`, Lizenz `LICENSE_ACTIVE` oder
`LICENSE_MISSING` mit klarer Ursache.

---

## 5. Lizenz

### 5.1 Aufbau

Der Lizenzschlüssel ist ein kompakter Token aus drei Teilen:

```text
base64url(header).base64url(payload).base64url(HMAC-SHA256)
```

Enthalten sind `licenseId`, `customer`, `instanceId`, `plan`, `maxAgents`,
`maxRunsPerMonth`, `features`, `issuedAt` und optional `expiresAt`. Signiert wird mit
`LICENSE_SECRET`; der Schlüssel ist an die `instance-id` der Installation gebunden.
Dasselbe Geheimnis liegt auf dem Server, ein Kunde mit Root-Zugriff könnte sich theoretisch
selbst einen Schlüssel ausstellen. Die Lizenz ist eine vertragliche und betriebliche
Kontrolle, kein DRM — diese Grenze wird offen benannt.

`instance-id` anzeigen:

```bash
cat /opt/firmspace-runtime/runtime-data/instance-id
```

### 5.2 Standardmäßig unbefristet

Kaufmännisch ist die Lizenz **unbefristet**: keine jährliche Verlängerung. Technisch bedeutet
das, dass im Schlüssel **kein `expiresAt`** steht. Ein solcher Schlüssel ist immer
`LICENSE_ACTIVE`; es gibt keine Kulanzfrist und keine Ablaufwarnung.

```bash
curl -fsS "$BASE_URL/api/v1/license" \
  | jq '{state, perpetual, expiresAt, daysRemaining, revokedAt}'
# perpetual: true, expiresAt: null
```

Ist ausnahmsweise eine Laufzeit vereinbart, stellt Firmspace einen Schlüssel mit `expiresAt`
aus (z. B. `2027-12-31`). Nur ein solcher Schlüssel durchläuft `LICENSE_GRACE` und
`LICENSE_READ_ONLY`. Die Standardform des Kabinetts hat kein Laufzeitfeld.

### 5.3 Schlüssel einsetzen oder ersetzen

**Der Hauptweg ist die Admin-Seite, ohne Neustart.** Sie prüft den Schlüssel vor dem
Schreiben und speichert ihn unter `/app/data/license.key` mit Rechten `600`.

1. `http://<host>:8080/runtime-admin/` öffnen (§6).
2. Admin-Token eingeben und **Prüfen** anklicken.
3. Im Block **Lizenz** den Schlüssel einfügen und **Installieren** anklicken.
4. Warten, bis **Lizenz installiert** erscheint. Der Schlüssel wirkt sofort in der laufenden API.

**Alternative für Automatisierung — die Env-Datei.** Sie erfordert einen Neustart:

1. Env-Datei öffnen:

   ```bash
   sudo editor /opt/firmspace-runtime/runtime.env
   ```

2. Füllen:

   ```dotenv
   LICENSE_KEY=<Schlüssel aus dem Kabinett>
   LICENSE_SECRET=<HMAC-Geheimnis, ≥ 32 Zeichen>
   ```

3. API neu starten:

   ```bash
   cd /pfad/zu/burokratie-copilot
   sudo docker compose --env-file /opt/firmspace-runtime/runtime.env \
     -f infra/docker-compose.runtime.yml up -d api
   ```

4. Prüfen:

   ```bash
   curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq
   ```

> **Die beiden Wege nicht mischen.** Ist `LICENSE_KEY` in `runtime.env` gesetzt, hat er
> Vorrang vor der Datei aus der Admin-Seite und gilt nach einem Neustart wieder. Für die
> Verwaltung über `/runtime-admin` die Zeile `LICENSE_KEY` entfernen und
> `LICENSE_KEY_FILE=/app/data/license.key` stehen lassen (in Compose der Standard).
> `LICENSE_SECRET` wird nicht auf der Admin-Seite eingegeben; er bleibt in der Env-Datei.

> **Wenn der Schlüssel nur über die Seite kommen soll.** Bei der Installation
> `--license-secret-file` übergeben, aber nicht `--license-key-file`: `LICENSE_SECRET` wird
> für die Prüfung gebraucht, `LICENSE_KEY` bleibt leer. Die Installation startet in
> `LICENSE_MISSING`, der erste Schlüssel wird auf der Seite eingefügt.


Prüfen, welche Datei verwendet wird:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_KEY_FILE
ls -l /opt/firmspace-runtime/runtime-data/license.key
```

### 5.4 Zustände

| Zustand | Bedeutung | Was tun |
|---|---|---|
| `LICENSE_ACTIVE` | Schlüssel gültig; bei unbefristeten Lizenzen immer | arbeiten |
| `LICENSE_GRACE` | Laufzeit abgelaufen, 14 Tage Kulanz; nur mit `expiresAt` | Schlüssel vor Ablauf der Kulanz ersetzen |
| `LICENSE_READ_ONLY` | Kulanz vorbei; nur mit `expiresAt` | Lesen ja, Schreiben und Läufe nein |
| `LICENSE_REVOKED` | Kabinett hat die Lizenz widerrufen | neuen Schlüssel installieren; keine Kulanz |
| `LICENSE_MISSING` | kein oder ungültiger Schlüssel | `reason` in `/api/v1/license` prüfen, Schlüssel einsetzen |
| `LICENSE_NOT_ENFORCED` | Prüfung aus | nur Entwicklung/Test; in Runtime nicht verwendet |

`LICENSE_GRACE_DAYS` ist standardmäßig `14`. `LICENSE_MISSING` blockiert den
Dokumenteingang nicht: Dokumente werden gespeichert, bis der Schlüssel kommt.
`LICENSE_REVOKED` schließt die Agenten-Oberfläche (`402` mit `LICENSE_REVOKED`), lässt aber
den Dokumenteingang offen: Eingang ist keine Ausführung.

### 5.5 Lizenz widerrufen

1. Kabinett des Inhabers öffnen (für Bridges und Lizenzen:
   `https://agents.firmspace.de`).
2. **Lizenzen** öffnen.
3. Lizenz über `licenseId` oder `instanceId` suchen.
4. **Widerrufen** anklicken und einen Grund angeben.

`[SCREENSHOT]` Lizenzliste und Widerrufsdialog im Inhaber-Kabinett.

**Wie es die Runtime erreicht.** Etwa eine Minute nach dem Start und danach alle **6 Stunden** fragt die API den
öffentlichen Lizenzstatus im Kabinett ab (`LICENSE_CHECK_URL`) und übermittelt nur
`licenseId` und `instanceId`. Die Antwort `active:false` schaltet die Installation auf
`LICENSE_REVOKED` ohne Kulanz. Die letzte Antwort wird in
`/app/data/license-state.json` zwischengespeichert, damit ein Neustart während einer
Kabinett-Störung den Widerruf nicht vergisst. Ist das Kabinett nicht erreichbar, bleibt der
letzte bekannte Zustand: Eine Störung beim Anbieter schaltet den Kunden nicht ab.

**Was vor dem Kundenversprechen zu prüfen ist.** Die aktuelle Compose-Datei übergibt
`LICENSE_CHECK_URL` nicht standardmäßig an den Container. Prüfen:

```bash
docker exec firmspace-robots-runtime-api printenv LICENSE_CHECK_URL
```

Bleibt die Ausgabe leer, ist die Fernprüfung aus und ein Widerruf im Kabinett erreicht diese
Installation nicht. Zum Einschalten die Variable in die Umgebung des `api`-Dienstes geben:

```dotenv
LICENSE_CHECK_URL=https://agents.firmspace.de/api/license/status
```

und `api` neu starten. Die genaue Status-URL nennt Firmspace mit dem Kabinett; das ist das
Beispiel aus dem Code. Ohne sie bleiben nur Schlüsselaustausch und Ablauf (falls gesetzt).

### 5.6 Woher der Inhaber den Schlüssel nimmt

1. Im Inhaber-Kabinett: **Lizenzen → Ausstellen**.
2. `customer`, `instanceId` (aus `runtime-data/instance-id`), Plan und Limits angeben.
3. Für eine unbefristete Lizenz wird kein `expiresAt` gesetzt. Wurde eine Laufzeit
   vereinbart, stellt Firmspace den Schlüssel mit `expiresAt` aus (die Standardform des
   Kabinetts zeigt möglicherweise kein Laufzeitfeld).
4. `LICENSE_KEY` kopieren und zusammen mit `LICENSE_SECRET` über einen sicheren Kanal
   übergeben.
5. Der Kunde installiert den Schlüssel über `/runtime-admin` (§6) oder `runtime.env` (§5.3).

`[SCREENSHOT]` Formular zum Ausstellen: customer, instanceId, plan, Limits, features.

---

## 6. Admin-Oberfläche

### 6.1 Was sie ist

Der Schrägstrich am Ende ist wichtig: Die Seite lädt `styles.css` und `app.js` relativ zum
Verzeichnis `/runtime-admin/`. Ohne ihn liefert der Controller zwar die Seite, aber Stil und
Skript können fehlen.

Die Lieferung enthält eine Administrationsseite: `http://<host>:8080/runtime-admin/`. Sie
gehört zum Runtime-Profil und funktioniert ohne Browser-Sitzung: Jede Aktion sendet den
Header `X-Runtime-Admin-Token` an `/api/v1/runtime/**`, wo die Autorisierung stattfindet.
Die Seite enthält vor der Anmeldung keine Daten; der Token liegt nur im `sessionStorage` des
Tabs und nicht im `localStorage`.

Die Seite bietet:

- **Installationsstatus**: Version, `instanceId`, Health, Einrichtungsstatus, Mandant,
  Workspace, E-Mail des Inhabers, aktive Tokens.
- **Lizenz**: Schlüssel einfügen, installieren, Zustand, Laufzeit und Warnungen sehen.
- **Ersteinrichtung**: Unternehmen, Workspace, Inhaber und ersten Token anlegen.
- **API-Tokens**: ausstellen, widerrufen, Wert einmal kopieren.
- **Bridge verbinden**: fertiger `forward.copilot`-Block und Docker-Befehle.

### 6.2 Schritt für Schritt

1. `http://<host>:8080/runtime-admin/` öffnen.
2. Im Feld **Admin-Token** den `BC_RUNTIME_ADMIN_TOKEN` aus `runtime.env` einfügen und
   **Prüfen** anklicken. Der **Installationsstatus** erscheint.
3. Block **Lizenz**: Schlüssel in **Lizenzschlüssel** einfügen, **Installieren** anklicken.
   Es muss **Lizenz installiert** erscheinen; darunter Zustand, Datei
   `/app/data/license.key` und Hinweise für den Betreiber.
4. Ist die Installation noch nicht eingerichtet, im Block **Ersteinrichtung**
   **Unternehmensname**, **E-Mail des Inhabers**, optional **Name des Inhabers** ausfüllen
   und **Installation anlegen** anklicken. Der Token wird einmal angezeigt — kopieren.
5. Block **API-Tokens**: **Token-Name** eingeben, Scopes wählen (`documents:write` für die
   Bridge, `agents.run` für Läufe, `settings:write` nur für vertrauenswürdige Integrationen),
   **Token erstellen** anklicken und den Wert sofort kopieren.
6. Block **Bridge verbinden** zeigt `forward.copilot` und Befehle; den Image-Tag durch die
   eigene Version ersetzen.

`[SCREENSHOT]` Vier Bildschirme: Anmeldung per Admin-Token, Status, Lizenzinstallation,
Ersteinrichtung und Tokens.

### 6.3 Dasselbe über die API

Die Admin-API nimmt den Header `X-Runtime-Admin-Token`, keinen Bearer-Token.

```bash
cd /pfad/zu/burokratie-copilot
set -a; . /opt/firmspace-runtime/runtime.env; set +a
ADMIN=(-H "X-Runtime-Admin-Token: $BC_RUNTIME_ADMIN_TOKEN")
BASE="http://127.0.0.1:${BC_API_PORT:-8080}"

# 1. Zustand: bootstrapped, version, tenant, license, adminTokenConfigured
curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/status" | jq

# 2. Lizenz ohne Neustart installieren
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/license" \
  -d '{"licenseKey":"<Schlüssel aus dem Kabinett>"}' | jq

# 3. Tokens ohne Geheimnisse
curl -fsS "${ADMIN[@]}" "$BASE/api/v1/runtime/tokens" | jq

# 4. Neuer Token
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"bridge-office-1","scopes":["agents.view","agents.run","documents:write"]}' | jq

# 5. Token widerrufen
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "${ADMIN[@]}" \
  "$BASE/api/v1/runtime/tokens/<TOKEN_ID>/revoke"
```

Erwartete Antworten:

- `GET /status` → `bootstrapped`, `version`, `tenantId`, `workspaceId`, `license.state`,
  `adminTokenConfigured`.
- `POST /license` → `installed:true`, `keyFile`, `state`, `warnings`, `license`.
- `POST /tokens` → HTTP `201` mit `id`, `token`, `prefix`, `scopes`,
  `rateLimitPerMinute`.
- `POST /tokens/{id}/revoke` → HTTP `204`.

Ist `BC_RUNTIME_ADMIN_TOKEN` leer, antworten die Admin-Endpunkte mit `404` (Oberfläche
verborgen). Ist der Token falsch, kommt `401`.

---

## 7. Erster Zugang, Unternehmen und Tokens

### 7.1 Was der Installer bereits angelegt hat

Beim ersten Lauf legt der Bootstrap in einer Transaktion an:

- Tenant mit dem angegebenen Namen;
- Standard-Workspace;
- Owner ohne Passwort (Identität für Audit und Token-Eigentum, kein Login);
- ersten API-Token mit den Scopes `agents.view`, `agents.run`, `documents:write`;
- Limit von `120` Anfragen pro Minute für diesen Token.

Ein erneutes `POST /api/v1/runtime/bootstrap` liefert `409`: Die Installation ist bereits
gebootstrappt. Neue Token über `/api/v1/runtime/tokens` ausstellen.

### 7.2 Unternehmen manuell anlegen

```bash
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/bootstrap" \
  -d '{
        "tenantName": "Muster GmbH",
        "ownerEmail": "admin@example.de",
        "ownerName": "Admin Muster"
      }' | jq
```

Die Antwort `201` enthält `tenantId`, `workspaceId`, `ownerUserId`, `tokenId`, `token`,
`prefix`, `scopes`, `rateLimitPerMinute`, `apiBase`. Das Feld `token` existiert nur in
dieser Antwort.

### 7.3 Ersten API-Token manuell ausstellen

```bash
curl -sS -X POST "${ADMIN[@]}" -H 'Content-Type: application/json' \
  "$BASE/api/v1/runtime/tokens" \
  -d '{"name":"ingest-scanner","scopes":["documents:write"]}' | jq
```

Mit dem Token prüfen:

```bash
export TOKEN='<Token aus der Antwort>'
curl -fsS "$BASE/api/v1/agents" -H "Authorization: Bearer $TOKEN" | jq '.[0]'
```

### 7.4 Scopes

| Scope | Öffnet |
|---|---|
| `documents:write` | `POST /api/v1/documents:ingest` sowie Anlegen/Prüfen von Dokumenten |
| `documents:read` | Lesen von Dokumenten (`GET /api/v1/documents...`) |
| `agents.view` | Agentenkatalog, Laufprotokolle, Lesen von Workflow und Konstellationen |
| `agents.run` | Start von Agenten, Workflow, Instanziierung von Konstellationen, `resume` |
| `exports:read` | `GET /api/v1/exports/elster` |
| `settings:read` | Lesen der Einstellungen |
| `settings:write` | Ändern von Regeln, Datumsbegriffen und Retention |

Regeln:

- Ein Token pro Integration. `settings:write` nicht an Bridge oder Scanner geben.
- Rechte lassen sich nach dem Anlegen nicht ändern: widerrufen und neu ausstellen.
- Höchstens **20 aktive Token** pro Workspace.
- Der Token wird einmal angezeigt; in der Datenbank liegt nur der SHA-256. Bei Verlust:
  widerrufen und neu ausstellen.
- Der Tenant kommt aus dem Token; ein Header kann ihn nicht ersetzen.

---

## 8. Prüfen, ob alles läuft

```bash
export BASE_URL="http://192.168.1.20:8080"
export TOKEN='<Ihr Token>'

# Lizenz
curl -fsS "$BASE_URL/api/v1/license" | jq '{state, perpetual, plan, expiresAt, revokedAt}'

# Agentenkatalog
curl -fsS "$BASE_URL/api/v1/agents" -H "Authorization: Bearer $TOKEN" | jq '.[0]'

# Datei einliefern
curl -sS -X POST "$BASE_URL/api/v1/documents:ingest" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -H "X-Filename: rechnung.pdf" \
  -H "Content-Type: application/pdf" \
  --data-binary @rechnung.pdf | jq

# Agent starten
curl -sS -X POST "$BASE_URL/api/v1/agents/doc-detect/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"trigger":"manual","inputs":{"fileName":"rechnung.pdf"}}' | jq
```

Erwartet: Lizenz `LICENSE_ACTIVE`, Katalog als Array, Ingest `201` mit `documentId`, Lauf
`ok: true` oder `ok: false` mit klarer `summary`. `ok: false` ist eine reguläre
Geschäftsantwort des Agenten, kein HTTP-Fehler.

---

## 9. Bridge an die Runtime anbinden

Es gibt zwei verschiedene Wege, die nicht verwechselt werden dürfen.

### 9.1 Bridge als Dokumentquelle (Forward)

Die Bridge kann eine erfasste Datei zusätzlich an einen Copilot-kompatiblen Ingest senden.
Das funktioniert auch mit der Runtime:

```jsonc
{
  "console": {
    "url": "https://agents.firmspace.de",
    "token": "${BRIDGE_TOKEN}"
  },
  "forward": {
    "copilot": {
      "enabled": true,
      "url": "http://192.168.1.20:8080",
      "token": "${RUNTIME_INGEST_TOKEN}",
      "timeoutMs": 30000
    }
  }
}
```

- `url` — Basis-URL der Runtime; die Bridge ergänzt `/api/v1/documents:ingest`.
- `token` — Runtime-API-Token mit Scope `documents:write`.
- Der Versand ist best-effort: Ein Fehler der Runtime verliert die Datei lokal nicht.
- Im Ereignis muss `contentBase64` stehen; für `folder`/`email` `includeContent: true`
  einschalten.

### 9.2 Direkter Aufruf aus eigenem Code

Ohne Bridge direkt ingest aufrufen — Vertrag in [EMBEDDED_API.md](EMBEDDED_API.md). Für die
Runtime ist die Basis-URL die Adresse aus §3.

> Im Runtime-Profil gibt es keine Konsolen-Endpunkte `/api/bridge/*`. Die Bridge kann das
> Roboter-Kabinett nicht ersetzen: Sie braucht weiterhin `console.url` für Heartbeat und
> Aufträge. Eine direkte Verbindung „Bridge ↔ Runtime“ ohne Kabinett gehört nicht zu dieser
> Lieferung.

---

## 10. Update, Sicherung, Wiederherstellung

### 10.1 Update

```bash
cd /pfad/zu/burokratie-copilot
git pull                      # oder neues Lieferarchiv entpacken
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml build
docker compose --env-file /opt/firmspace-runtime/runtime.env \
  -f infra/docker-compose.runtime.yml up -d
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
```

Vorher sichern. Flyway arbeitet forward-only: Ein Schema-Rollback ist nur über eine
Wiederherstellung aus dem Dump möglich. Lizenz und `instance-id` liegen außerhalb des Images
und bleiben erhalten.

### 10.2 Sicherung

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

`runtime.env` enthält Datenbankpasswort, AES-Schlüssel und Admin-Token; Lizenzschlüssel und
Zustand liegen in `runtime-data` (`license.key`, `license-state.json`). Die Sicherung vor dem
Verlassen des Servers verschlüsseln.

### 10.3 Wiederherstellung

```bash
"${COMPOSE[@]}" stop api
"${COMPOSE[@]}" exec -T postgres pg_restore -U "$BC_DB_USER" -d "$BC_DB_NAME" \
  --clean --if-exists --no-owner < database.dump
sudo tar xzf runtime-data.tgz -C "$(dirname "$BC_DATA_DIR")"
"${COMPOSE[@]}" up -d api
curl -fsS "http://127.0.0.1:8080/actuator/health/readiness"
curl -fsS "http://127.0.0.1:8080/api/v1/license" | jq '.state'
```

Mehr dazu in [MANAGEMENT.md](MANAGEMENT.md) und [../runbook.md](../../runbook.md).

---

## 11. Wenn es nicht funktioniert

| Symptom | Vermutliche Ursache | Was tun |
|---|---|---|
| Installer: `docker daemon is not reachable` | Docker nicht gestartet oder keine Rechte | `sudo systemctl start docker`; `docker`-Gruppe prüfen |
| Installer: `runtime.env already exists` | Installation existiert bereits | für neuen Token `scripts/bootstrap-runtime.sh`; `--force` nur auf frischer Installation |
| `API did not become healthy in 240s` | API startet nicht, Datenbank nicht erreichbar | `docker compose ... logs --tail=120 api`; `ps`; Datenbankpasswort prüfen |
| `/actuator/health/readiness` = 503 | Migrationen/Datenbank/Speicher | API-Logs, `df -h`, `docker compose ... ps` |
| Agenten antworten `402 LICENSE_MISSING` | kein Schlüssel | §5.3; `reason` in `/api/v1/license` prüfen |
| Agenten antworten `401` | Token falsch/widerrufen | neuen Token ausstellen; `Authorization: Bearer` prüfen |
| Agenten antworten `403` | Scope fehlt | `agents.view`/`agents.run` vergeben |
| Agenten antworten `402 LICENSE_REVOKED` | Kabinett hat die Lizenz widerrufen | neuen Schlüssel installieren; `LICENSE_CHECK_URL` und `revokedAt` prüfen |
| `/runtime-admin` antwortet `404` | API läuft nicht im Runtime-Profil | `SPRING_PROFILES_ACTIVE=runtime` und API-Logs prüfen |
| Admin-API antwortet `404` | `BC_RUNTIME_ADMIN_TOKEN` leer | Umgebungsdatei prüfen, API neu starten |
| Admin-API antwortet `401` | Admin-Token falsch | Wert vollständig aus `runtime.env` übernehmen |
| Port belegt | anderer Prozess auf 8080 | `--api-port 8081` oder Port freigeben |
| API schreibt nicht nach `/app/data` | Rechte am Verzeichnis | `sudo chown -R 10001:10001 /opt/firmspace-runtime/runtime-data` |

---

## 12. Abnahme-Checkliste

- [ ] Docker 24+ und Compose v2 mit Befehlen geprüft.
- [ ] `--public-url` entspricht der Adresse, die Clients tatsächlich nutzen.
- [ ] TLS-Proxy steht vor der API; Port 8080 nicht ins Internet veröffentlicht.
- [ ] `runtime.env` hat Rechte `600` und liegt in der Sicherung.
- [ ] `instance-id` gesichert; Lizenz daran gebunden.
- [ ] `http://<host>:8080/runtime-admin/` öffnet sich; der Admin-Token wird akzeptiert.
- [ ] `GET /api/v1/license` → `LICENSE_ACTIVE`; bei unbefristet `perpetual: true`,
      `expiresAt: null`.
- [ ] Schlüssel über die Seite oder die Env-Datei installiert; `/app/data/license.key`
      mit Rechten `600`.
- [ ] Entscheidung zu `LICENSE_CHECK_URL` dokumentiert: Widerrufsprüfung an oder bewusst aus.
- [ ] Bootstrap hat genau einen Tenant angelegt; `bootstrapped: true`.
- [ ] Erster API-Token im Secret Store, nicht im Ticket.
- [ ] `POST /documents:ingest` und `POST /agents/doc-detect/run` geprüft.
- [ ] Sicherung von Datenbank und `runtime-data` erstellt, Wiederherstellung geprüft.
- [ ] Monitoring für Health/Lizenz/Speicher eingerichtet.
- [ ] Verantwortlicher für Server und Update-Ablauf benannt.
