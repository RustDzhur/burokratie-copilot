# Bereitstellungsmodelle: eine Anleitung für Menschen ohne Vorkenntnisse

**Sprachen:** [Русский](../ru/README.md) · [Deutsch](README.md) · [English](../en/README.md)

**Stand:** 28. Oktober 2026.
**Geprüft gegen:** `scripts/install-runtime.sh`, `scripts/bootstrap-runtime.sh`,
`infra/docker-compose.runtime.yml`, `ApiTokenController`, `RuntimeAdminController`,
`ElsterExportController`, `ApiTokenAuthenticationFilter`, `LicenseService` sowie die
Bridge-Dokumentation (`firmspace-bridge`, Version 0.1.0).

Dieser Bereich erklärt die **vier Bereitstellungsmodelle** von Firmspace /
Bürokratie-Copilot so, dass man ohne Erfahrung entscheiden und installieren kann: was man
bekommt, wo es läuft, wie die Basis-URL lautet und wo jeder Token herkommt. Kein Marketing,
sondern Aussagen, die sich mit einem Befehl oder im Interface prüfen lassen.

![Übersicht der vier Bereitstellungsmodelle](../../images/deployment-models.svg)

*Abbildung 1. Die vier Modelle und der wichtigste Unterschied: wo die Daten liegen und wer
den Server betreibt.*

---

## 1. Worum es geht und für wen es gedacht ist

Firmspace lässt sich auf vier Arten bereitstellen, die sich nicht gegenseitig ersetzen.
**Nur Bridge** verbindet eine bestehende Altsystem-Landschaft mit der Plattform, erkennt
aber nichts. **Robots Runtime** bringt die Agenten-Engine und die Dokumente auf den Server
des Kunden. **Embedded API** gibt dem eigenen Produkt HTTP-Zugriff auf Erkennung und
Agenten. **Copilot als Service** liefert ein fertiges Cloud-Kabinett ohne eigene
Infrastruktur. Die Entscheidung hängt an zwei Fragen: Dürfen Dokumente den Kundenbereich
verlassen, und wer betreibt den Server? Die folgenden Abschnitte beantworten beide Fragen,
bevor installiert wird.

---

## 2. Was man braucht

### 2.1 Mindestens, für jedes Modell

| Was | Anforderung | Prüfung |
|---|---|---|
| Browser | aktuelles Chrome, Firefox, Edge oder Safari | `https://burokratie.firmspace.de` öffnen |
| `curl` | 7.76 oder neuer | `curl --version` |
| Uhrzeit | NTP, Abweichung unter 5 Minuten | `timedatectl status` (Linux) |
| Verbindung | ausgehend HTTPS 443; keine eingehenden Ports nötig | `curl -I https://burokratie.firmspace.de` |
| Ort für Geheimnisse | Passwortmanager oder Datei mit Rechten `600` | — |

### 2.2 Zusätzlich für Runtime (On-Premises)

| Was | Anforderung | Prüfung |
|---|---|---|
| Betriebssystem | Linux x86_64/arm64 (empfohlen) | `uname -m` |
| Docker Engine | 24 oder neuer | `docker version --format '{{.Server.Version}}'` |
| Docker Compose | v2 | `docker compose version` |
| Arbeitsspeicher | mindestens 4 GB, empfohlen 8 GB | `free -h` |
| Speicherplatz | mindestens 10 GB frei, empfohlen 200 GB | `df -h` |
| `openssl` | aktuelle Version | `openssl version` |
| `git` | für die Quelldateien der Lieferung | `git --version` |
| PostgreSQL | 17, im Compose enthalten | `docker compose ... exec postgres psql --version` |

### 2.3 Zusätzlich für die Bridge

| Was | Anforderung | Prüfung |
|---|---|---|
| Docker Engine | 24 oder neuer | `docker version` |
| Architektur | `amd64` oder `arm64` | `uname -m` |
| Arbeitsspeicher | 512 MB plus Reserve | `docker info` |
| Speicherplatz | 2 GB plus Platz für die Warteschlange | `df -h` |
| Node.js | 22 LTS — **nur** für den Betrieb ohne Docker | `node -v` |
| Zugriff auf Ordner | UID/GID 1000 im Container | `id -u` auf dem Host |

### 2.4 Zusätzlich für Embedded API

| Was | Anforderung | Prüfung |
|---|---|---|
| TLS | gültiges Zertifikat für die API-Domain | `openssl s_client -connect <host>:443 -servername <host>` |
| `jq` | optional, aber für die Beispiele praktisch | `jq --version` |
| Token | mit den nötigen `scopes` ausgestellt | `curl ... /api/v1/agents` |

> **Wichtig.** In allen Beispielen stehen nur Platzhalter: `${BASE_URL}`, `${TOKEN}` und
> `<hier eigenen Wert einsetzen>`. Echte Token gehören nicht in die Kommandozeile, in die
> Shell-History, auf Screenshots oder in Tickets.

---

## 3. Das passende Modell wählen

### 3.1 Auswahltabelle

| Situation des Kunden | Modell | Beim Kunden installiert | Wo die Engine läuft | Wohin die Daten gehen | Token |
|---|---|---|---|---|---|
| Altsystem ohne API, nur Austausch nötig | [Nur Bridge](BRIDGE_ONLY.md) | Firmspace Bridge | Nirgends beim Kunden | Nur an den konfigurierten Empfänger | Bridge-Token |
| OCR, Agenten und Protokolle nötig, Dokumente dürfen den Kundenbereich nicht verlassen | [Robots Runtime](RUNTIME.md) | API + PostgreSQL 17 | Auf dem Server des Kunden | Bleiben im Kundenbereich | Runtime-API-Token |
| Das eigene Produkt soll Erkennung und Agenten aufrufen | [Embedded API](EMBEDDED_API.md) | Nur Client-Code | In SaaS oder Runtime | In die Instanz, für die der Token gilt | Copilot- oder Runtime-Token |
| Schneller Start ohne Infrastruktur | [Copilot als Service](COPILOT_SAAS.md) | Nichts, nur Browser | Bei Firmspace | In die Firmspace-Cloud (EU) | Copilot-API-Token |
| Agenten und Zugriff auf Altsysteme | Runtime + Bridge | API + PostgreSQL + Bridge | Auf dem Server des Kunden | Bleiben im Kundenbereich | Zwei Token, je Komponente einer |
| Agenten in der Cloud, Altsystem vor Ort | Copilot + Bridge | Nur Bridge | Bei Firmspace | In die Firmspace-Cloud | Zwei Token |

> **Hybrid wird nicht geliefert.** Eine Engine lässt sich nicht so auf Kunden-Server und
> Cloud aufteilen, dass beide denselben Speicher und denselben Tenant nutzen. Kombinierbar
> sind **ganze** Modelle: Runtime + Bridge, Copilot + Bridge.

### 3.2 Drei Fragen statt Tabelle

1. **Dürfen Dokumente den Kundenbereich verlassen?**
   - Nein → Runtime oder Embedded API in der Umgebung des Kunden.
   - Ja → Copilot als Service.
2. **Wer schreibt den aufrufenden Code?**
   - Unser Produkt oder ein Integrator → Embedded API.
   - Niemand, es wird eine fertige Oberfläche gebraucht → Copilot als Service.
   - Nur Austausch mit Altsystemen → Nur Bridge.
3. **Werden OCR, Kategorien und Agenten gebraucht?**
   - Nein → Nur Bridge.
   - Ja → Runtime (On-Premises) oder Copilot (Cloud).

---

## 4. Drei Adressen, die man kennen muss

Genau das fehlte in der bisherigen Dokumentation: die **Basis-URL** und der **Ort des
Tokens**. Drei Adressen genügen.

| Was | Adresse | Prüfung |
|---|---|---|
| Copilot in der Cloud | `https://burokratie.firmspace.de` | `curl -I https://burokratie.firmspace.de` |
| Copilot-API | `https://burokratie.firmspace.de/api/v1` | `curl -fsS https://burokratie.firmspace.de/api/v1/license` |
| Roboter-Kabinett (Bridges) | `https://agents.firmspace.de` | `curl -I https://agents.firmspace.de` |
| Runtime On-Premises | Adresse aus dem Installer, z. B. `http://192.168.1.20:8080` | `curl -fsS http://192.168.1.20:8080/actuator/health/readiness` |
| Runtime-API | `<Runtime-Adresse>/api/v1` | `curl -fsS http://192.168.1.20:8080/api/v1/license` |

Wenn das Terminal geschlossen ist, steht die Runtime-Adresse in der Umgebungsdatei:

```bash
cd /pfad/zu/burokratie-copilot
set -a; . infra/runtime.env; set +a
printf '%s\n' "$BC_PUBLIC_BASE_URL"
```

Bei einer Installation mit `--dir /opt/firmspace-runtime` liegt die Datei unter
`/opt/firmspace-runtime/runtime.env`. Darin steht `BC_PUBLIC_BASE_URL`; das ist die
Basis-URL. Die API ergänzt immer `/api/v1`.

Die drei Token liegen an drei verschiedenen Orten:

| Token | Woher | Wohin |
|---|---|---|
| Copilot-API | Kabinett → Kontoeinstellungen → API-Tokens → Token anlegen | Header `Authorization: Bearer ${TOKEN}` |
| Bridge | Roboter-Kabinett → Unternehmen → Verbindungen → Bridge hinzufügen | `bridge.config.json` und `.env` (`BRIDGE_TOKEN`) |
| Runtime-Admin | Der Installer erzeugt ihn und legt ihn in `infra/runtime.env` ab | Header `X-Runtime-Admin-Token` |

Schritt für Schritt mit Bild: [Abbildung „Woher der Token kommt“](../../images/token-locations.svg).

---

## 5. Was in welchem Dokument steht

| Dokument | Für wen | Inhalt |
|---|---|---|
| [README.md](README.md) | alle | Modellwahl, Adressen, Token, Wegweiser |
| [BRIDGE_ONLY.md](BRIDGE_ONLY.md) | IT des Kunden | Image arm64/amd64, `docker load`, Import der `bridge.config.json`, 10 typische Fehler |
| [RUNTIME.md](RUNTIME.md) | IT des Kunden, Betreiber | Installation, Seite `/runtime-admin/`, Lizenz, erster Token, Sicherungen |
| [EMBEDDED_API.md](EMBEDDED_API.md) | Entwickler | Basis-URL, Scopes, curl, Fehler 401/402/403/409/429, Idempotenz, ELSTER |
| [COPILOT_SAAS.md](COPILOT_SAAS.md) | Inhaber, Administrator | Registrierung, Dokumente, Post, Token, Tarife, Daten |
| [MANAGEMENT.md](MANAGEMENT.md) | Inhaber, Support | Lizenzen, Updates, Sicherungen, Protokolle, Vorfälle, RACI |
| [FAQ.md](FAQ.md) | alle | 25 Fragen: URL, Token, Lizenz, Offline, Daten, Berichte |

Für Technik und Betrieb zusätzlich: [../deployment.md](../../deployment.md) (Aufbau des
Self-Hosted-Stacks), [../runbook.md](../../runbook.md) (Betrieb),
[../security.md](../../security.md) (Sicherheit),
[../compliance/README.md](../../compliance/README.md) (DSGVO).

---

## 6. Wie man diese Dokumente liest

- **Platzhalter.** `${BASE_URL}` ist die API-Adresse ohne abschließenden `/`. `${TOKEN}` ist
  das Geheimnis aus dem Kabinett. `<hier eigenen Wert einsetzen>` ist ein Wert, den man nicht
  erfinden kann.
- **Pfade.** Befehle laufen im Wurzelverzeichnis der Lieferung `burokratie-copilot`, sofern
  nicht anders angegeben. Pfade im Bridge-Container sind **Container-Pfade**, nicht Host-Pfade.
- **Erwartetes Ergebnis.** Nach jedem Befehl steht, was erscheinen muss. Etwas anderes ist
  kein Schönheitsfehler, sondern ein Grund anzuhalten und den Schritt zu prüfen.
- **Screenshots.** Stellen, für die ein Bild der laufenden Oberfläche nötig ist, sind mit
  `[SCREENSHOT]` markiert. In dieser Lieferung liegen keine solchen Bilder: Die Grafiken in
  `docs/images` zeigen den Weg, ersetzen aber nicht die Prüfung in der konkreten
  Kabinett-Version.
- **Versionen.** Die Bridge in den Beispielen ist `0.1.0`. Das Runtime-Image trägt den Tag,
  den Sie gebaut oder erhalten haben; halten Sie ihn im Abnahmeprotokoll fest.

---

## 7. Was geprüft ist und was eine Entscheidung braucht

**Geprüft gegen Code und Skripte am 28.10.2026:**

- Runtime-Installer: Parameter, Erzeugung von `BC_RUNTIME_ADMIN_TOKEN`, Schreiben von
  `infra/runtime.env` mit Rechten `600`, Aufruf des Bootstrap;
- Bootstrap: Tenant/Workspace/Owner, einmalige Ausgabe des ersten Tokens, Prüfung von
  `/actuator/health/readiness`, Ausgabe der `API URL`;
- Lizenz: HMAC-Token, Bindung an `instance-id`, unbefristeter Schlüssel ohne `expiresAt`,
  Zustände `LICENSE_ACTIVE`, `LICENSE_GRACE`, `LICENSE_READ_ONLY`, `LICENSE_REVOKED`,
  `LICENSE_MISSING`;
- Seite `/runtime-admin/` und `POST /api/v1/runtime/license`: Schlüssel ohne Neustart
  installieren, Datei `/app/data/license.key`, Warnungen für den Betreiber;
- Runtime-Admin-API: `POST /api/v1/runtime/bootstrap`, `POST /api/v1/runtime/tokens`,
  `POST /api/v1/runtime/tokens/{id}/revoke`, `GET /api/v1/runtime/status`;
- Copilot-Token: `GET/POST /api/v1/api-tokens`, `POST /api/v1/api-tokens/{id}/revoke`,
  Scopes, maximal 20 aktive Token, 60 Anfragen pro Minute;
- Ingest: `POST /api/v1/documents:ingest`, Pflicht-`Idempotency-Key`, SHA-256, Status
  201/200/400/409;
- ELSTER: `GET /api/v1/exports/elster?year=&format=json|csv`, CSV-Spalten;
- Bridge: CLI `init`, `import`, `test`, `doctor`, `status`, `run`, Pfade `/data/...`,
  Variable `BRIDGE_TOKEN`.

**Offen oder nur live zu prüfen:**

1. Genaue Dateinamen der Bridge-Archive und Download-Link — Firmspace übergibt sie mit der
   Lieferung; eine öffentliche Image-Registry gibt es nicht.
2. Die Fernprüfung des Widerrufs: Der Code liest `LICENSE_CHECK_URL` und fragt das Kabinett
   alle 6 Stunden, aber die aktuelle Compose-Datei übergibt die Variable nicht an den
   Container. Prüfen Sie `printenv LICENSE_CHECK_URL`; ohne sie erreicht ein Widerruf die
   Installation nicht.
3. Kaufmännische Lizenzbedingungen (Laufzeit, Plan, Preis) sind Vertragssache. Eine
   unbefristete Lizenz hat **kein** `expiresAt`; eine befristete enthält das Datum.
4. Supportkontakte, Zeiten und SLA sind in dieser Lieferung nicht gesetzt und gehören in den
   Vertrag.
5. Ein durchgängiger Produktionslauf jedes Modells beim Kunden steht vor der Abnahme aus.

---

## 8. Wie es weitergeht

1. Modell nach §3 wählen und im Vertrag oder Abnahmeprotokoll festhalten.
2. Das passende Dokument öffnen und die Schritte der Reihe nach ausführen.
3. Basis-URL und Token nach §4 prüfen — vor dem ersten Befehl.
4. Wenn etwas nicht funktioniert: zuerst [FAQ.md](FAQ.md), dann der Abschnitt „Wenn es nicht
   funktioniert“ im Dokument des Modells.
5. Für den Betrieb: [MANAGEMENT.md](MANAGEMENT.md).
