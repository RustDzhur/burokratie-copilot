# FAQ: 25 Fragen zu den Bereitstellungsmodellen

**Sprachen:** [Русский](../ru/FAQ.md) · [Deutsch](FAQ.md) · [English](../en/FAQ.md)

## Worum es geht und für wen es gedacht ist

Diese FAQ beantwortet die Fragen, die vor der Modellwahl und in den ersten Tagen nach der
Installation auftauchen: Wie lautet die Basis-URL, woher kommt der Token, was ist bei einem
Fehler zu tun und wohin gehen die Daten. Sie ist für Inhaber, Administratoren und die erste
Supportlinie geschrieben, nicht für Kernentwickler. Die Antworten sind kurz, enthalten aber
jeweils einen Befehl oder einen genauen Pfad im Interface, damit sie prüfbar bleiben. Für eine
vollständige Anleitung verweist jede Antwort auf das Dokument des Modells.

## Was man braucht

| Was | Anforderung | Prüfung |
|---|---|---|
| Browser | aktuelles Chrome, Firefox, Edge oder Safari | Kabinett öffnen |
| `curl` | 7.76+ | `curl --version` |
| Zugang | zum Kabinett oder zum Runtime-Server | Login / SSH |
| Geheimnis | Token oder `runtime.env` zur Hand | nicht in den Chat einfügen |
| Uhrzeit | NTP, Abweichung unter 5 Minuten | `timedatectl status` |

Kurze Antworten für Kunden, Vertrieb und die erste Supportlinie. Beginnt eine Antwort mit
„kommt darauf an“, steht daneben, wovon sie abhängt. Alle Befehle laufen auf einem Rechner mit `curl`;
Geheimnisse sind durch Platzhalter ersetzt.

![Die vier Bereitstellungsmodelle](../../images/deployment-models.svg)

*Abbildung 1. Vier Modelle: Auswahl nach Daten und Verantwortung für den Server.*

---

### 1. Wie lautet die Basis-URL?

Es gibt zwei Arten, die nicht austauschbar sind:

- **Copilot in der Cloud:** `https://burokratie.firmspace.de`; API derselbe Host plus
  `/api/v1`, also `https://burokratie.firmspace.de/api/v1`.
- **Runtime On-Premises:** die Adresse aus der Installer-Zeile `API URL:`, z. B.
  `http://192.168.1.20:8080`; API `<diese Adresse>/api/v1`.

Ist das Terminal geschlossen, steht die Runtime-Adresse in der Umgebungsdatei:

```bash
sudo grep '^BC_PUBLIC_BASE_URL=' /opt/firmspace-runtime/runtime.env
```

Prüfung: `curl -fsS "$BASE_URL/actuator/health/readiness"` → `{"status":"UP"}`.

---

### 2. Woher kommt der Token?

Es gibt drei Token an drei Orten:

| Token | Wo |
|---|---|
| Copilot-API | Kabinett → Kontoeinstellungen → API-Tokens → Token anlegen |
| Bridge | Roboter-Kabinett `https://agents.firmspace.de` → Unternehmen → Verbindungen → Bridge hinzufügen → `bridge.config.json` herunterladen |
| Runtime-Admin | Der Installer erzeugt ihn; er liegt in `runtime.env` als `BC_RUNTIME_ADMIN_TOKEN` |

`[SCREENSHOT]` Karte „API-Tokens“ und Dialog „Token angelegt“ — einmal aufnehmen und in der internen Anleitung ablegen.

Schritt für Schritt mit Bild: [README.md](README.md) §4 und
[Abbildung „Woher der Token kommt“](../../images/token-locations.svg).

---

### 3. Was ist, wenn der Token verloren ist?

Er lässt sich nicht wiederherstellen: Der Server kennt nur den SHA-256. Alten Token
widerrufen und einen neuen anlegen.

- Copilot: **API-Tokens** → Eintrag an Name/`prefix` erkennen → **Widerrufen** →
  **Token anlegen**.
- Runtime: `POST /api/v1/runtime/tokens/<id>/revoke`, dann neuer Token über
  `POST /api/v1/runtime/tokens`.
- Bridge: Bridge im Kabinett widerrufen, neu anlegen, neue `bridge.config.json`
  herunterladen, importieren, Container neu starten.

Nach dem Widerruf antwortet der alte Token beim nächsten Aufruf mit `401`.

---

### 4. Lässt sich ein Token aus der Datenbank wiederherstellen?

Nein. In der Datenbank liegt nur der Hash. „Noch einmal anzeigen“ ist deshalb technisch
unmöglich und keine vergessene Schaltfläche. Den Wert sofort beim Anlegen in den
Passwortmanager legen.

---

### 5. Was passiert, wenn die Lizenz widerrufen wird?

Der Inhaber widerruft sie im Kabinett (**Lizenzen → Widerrufen**). Die Runtime fragt beim
Start und danach alle 6 Stunden den öffentlichen Status über `LICENSE_CHECK_URL` ab und
wechselt bei `active:false` in `LICENSE_REVOKED`: Agenten und Workflow antworten `402`, ohne
Kulanzfrist. Die Prüfung wirkt nur, wenn `LICENSE_CHECK_URL` im Container ankommt (in der
aktuellen Compose-Datei mit `printenv LICENSE_CHECK_URL` prüfen). Ohne sie erreicht der
Widerruf die Installation erst beim Schlüsselaustausch. Mehr in
[RUNTIME.md](RUNTIME.md) §5.5.

---

### 6. Funktioniert es ohne Internet?

- **Runtime:** ja, für den normalen Betrieb. Ausgehendes Netz ist nur nötig, wenn der Kunde
  externe KI, E-Mail oder Updates einschaltet. Air-Gap ist möglich, Updates und Support
  brauchen dann einen eigenen Weg.
- **Copilot SaaS:** nein, reiner Cloud-Dienst.
- **Bridge:** arbeitet mit der lokalen Warteschlange weiter, wenn das Kabinett fehlt;
  Aufträge und Heartbeat brauchen aber Verbindung.

---

### 7. Wohin gehen die Daten?

- **Nur Bridge:** bleiben beim Kunden; sie gehen nur an den konfigurierten Empfänger.
- **Runtime:** bleiben im Kundenbereich; nach außen nur, wenn der Kunde externe KI, E-Mail
  oder eine Integration einschaltet.
- **Embedded API:** in die Instanz, für die der Token gilt (SaaS oder Runtime des Kunden).
- **Copilot SaaS:** in die Cloud-Instanz von Firmspace, Region laut Vertrag (Ziel EU).

Liste der Subunternehmer: [../compliance/subprocessors.md](../../compliance/subprocessors.md).

---

### 8. Wie prüfe ich, ob ein Dokument im Bericht landet?

Drei Stufen:

1. Antwort des Ingest: HTTP `201` und `documentId`.
2. Dokumentenregister: Dokument sichtbar, Kategorie und Felder vorhanden.
3. Bericht: **Berichte und Analyse** → **Ausgaben und ELSTER-Zuordnung** → Zeile mit dieser
   `document_id`; im CSV die Spalte `document_id`.

Ist das Dokument da, aber keine Zeile im Bericht: Jahr, Kategorie und Datum prüfen. Ein
Dokument ohne Kategorie/Datum wird nicht versteckt, sondern mit Warnung angezeigt. Ein leeres
Jahr ist `200` mit Nullen, kein Fehler.

---

### 9. Worin unterscheiden sich die vier Modelle?

| Modell | Engine | Daten | Oberfläche |
|---|---|---|---|
| Nur Bridge | keine | beim Kunden | keine |
| Robots Runtime | auf dem Kundenserver | beim Kunden | Admin-Seite `/runtime-admin` |
| Embedded API | SaaS oder Runtime | wo die Instanz läuft | beim Kunden |
| Copilot SaaS | in der Cloud | in der Cloud | fertiges Kabinett |

Ausführliche Tabelle: [README.md](README.md) §3.

---

### 10. Braucht man einen eigenen Server?

Nein für Copilot SaaS und Embedded API in der Cloud. Ja für Runtime und für die Bridge
(Rechner/Container beim Kunden). Minimum Runtime: 4 vCPU, 8 GB RAM, 200 GB SSD; Bridge:
512 MB RAM und 2 GB Speicher.

---

### 11. Welche Versionen werden gebraucht?

| Komponente | Version | Prüfung |
|---|---|---|
| Docker Engine | 24+ | `docker version --format '{{.Server.Version}}'` |
| Docker Compose | v2 | `docker compose version` |
| Node.js (Bridge ohne Docker) | 22 LTS | `node -v` |
| PostgreSQL | 17 (im Container) | `docker compose ... exec postgres psql --version` |
| `curl` | 7.76+ | `curl --version` |

---

### 12. Warum antwortet die API mit 401?

`401 UNAUTHENTICATED` heißt: Token fehlt, ist unbekannt, widerrufen oder abgelaufen. Prüfen:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/agents"
```

`401` → neuen Token ausstellen. `403` → Token gültig, aber Scope fehlt.

---

### 13. Warum antwortet die API mit 402?

`402` betrifft Lizenz/Tarif, nicht das Passwort:

- `LICENSE_MISSING` — in der Runtime fehlt der Schlüssel;
- `LICENSE_READ_ONLY` — Schlüssel abgelaufen, Kulanz vorbei;
- `LICENSE_REVOKED` — die Lizenz wurde im Kabinett widerrufen;
- `LICENSE_RUN_LIMIT` — Monatslimit für Läufe ausgeschöpft;
- `SUBSCRIPTION_REQUIRED`/`PLAN_LIMIT_REACHED` — SaaS-Tarif.

Prüfen: `curl -fsS "$BASE_URL/api/v1/license" | jq`, dann
[RUNTIME.md](RUNTIME.md) §5.

---

### 14. Warum antwortet die API mit 403?

Der Token ist gültig, aber der Scope fehlt. Für ELSTER braucht es `exports:read`, für den
Start `agents.run`, für den Upload `documents:write`. Rechte sind nach dem Anlegen
unveränderlich: neuen Token mit passendem Satz ausstellen.

---

### 15. Warum antwortet die API mit 429?

Ein Limit ist überschritten: standardmäßig 60 Anfragen pro Minute pro Token (120 für vom
Runtime-Admin ausgestellte) oder 600 pro Minute pro Benutzer/IP. Backoff mit Jitter, nicht
sofort wiederholen. `Retry-After` ist nicht immer vorhanden.

---

### 16. Was sind Scopes, und welches Minimum gilt?

Ein Scope ist ein Recht des Tokens. Das Minimum hängt von der Aufgabe ab:

- Datei-Upload — `documents:write`;
- Dokumente lesen — `documents:read`;
- Katalog/Protokolle — `agents.view`;
- Start — `agents.run`;
- ELSTER — `exports:read`.

`settings:write` nicht an Scanner oder Bridge geben. Ein Token, eine Aufgabe.

---

### 17. Wie viele Token sind erlaubt?

Höchstens **20 aktive** pro Workspace. Beim 21. kommt ein Fehler/`409`; einen unnötigen
widerrufen. Für Test- und Produktionsumgebung je ein eigener Token.

---

### 18. Gehen Runtime und Bridge zusammen?

Ja, das sind zwei ganze Modelle: Runtime auf dem Kundenserver und Bridge auf dem Rechner mit
der Altsystem-Software. Daten bleiben im Kundenbereich. Das ist kein „Hybrid“: Die Engine
wird nicht zwischen Kunde und Cloud geteilt.

---

### 19. Gehen Copilot und Bridge zusammen?

Ja: Copilot in der Cloud und Bridge beim Kunden. Dokumente gehen über die eingerichteten
Kanäle in die Cloud. Die Bridge bleibt Transport, nicht Engine.

---

### 20. Wie wird aktualisiert?

- **Copilot SaaS:** automatisch, der Kunde muss nichts tun.
- **Runtime:** `git pull` → `docker compose build` → `up -d`; vorher sichern.
- **Bridge:** neues Image laden, Container mit denselben Volumes neu anlegen,
  `bridge test` ausführen.
- **Embedded API:** aktualisiert sich mit der Instanz, mit der sie verbunden ist.

Details: [MANAGEMENT.md](MANAGEMENT.md) §6.

---

### 21. Wo liegen die Sicherungen?

- **Runtime:** PostgreSQL (`pg_dump -Fc`), `runtime-data`, `runtime.env`. Befehle in
  [MANAGEMENT.md](MANAGEMENT.md) §7.
- **Bridge:** `config/bridge.config.json`, `config/.env`, Volume `.bridge`.
- **Copilot SaaS:** Firmspace sichert; der Kunde exportiert seine Daten vor Vertragsende.
- **Embedded API:** Verantwortung des Instanz-Inhabers (SaaS oder Runtime).

---

### 22. Wie lange werden Daten aufbewahrt?

Das hängt von Modell und Vertrag ab. Bei Copilot SaaS gilt standardmäßig eine Testphase von
60 Tagen und eine Aufbewahrung nach Ende von 100 Tagen; genaue Fristen stehen im Vertrag und
in den Retention-Einstellungen. In der Runtime bestimmen Instanz-Einstellungen und Vertrag
die Frist; Sicherungen folgen der Politik des Kunden. Rechtliche Dokumente:
[../compliance/retention.md](../../compliance/retention.md).

---

### 23. Hat die Runtime eine Weboberfläche?

Ja. `http://<host>:8080/runtime-admin/` öffnen und `BC_RUNTIME_ADMIN_TOKEN` aus
`runtime.env` einfügen, dann **Prüfen**. Die Seite zeigt den Installationsstatus, erlaubt die
Lizenzinstallation, die Ersteinrichtung des Unternehmens und die Token-Verwaltung. Sie
braucht keine Browser-Sitzung: Jede Aktion sendet `X-Runtime-Admin-Token`, der Token liegt
nur im `sessionStorage` des Tabs. Schritte in [RUNTIME.md](RUNTIME.md) §6.

---

### 24. Kann man die Lizenz ohne Token ansehen?

Ja. `GET /api/v1/license` ist ohne Anmeldung erreichbar: Installer, Monitoring und der erste
Betreiber müssen den Zustand sehen, bevor ein Token existiert. Die Antwort enthält keinen
Schlüssel, keine Signatur und kein Geheimnis — nur Zustand, Plan, Laufzeit und `instanceId`.

```bash
curl -fsS "$BASE_URL/api/v1/license" | jq '{state, plan, expiresAt, daysRemaining}'
```

---

### 25. Was tun bei einem Vorfall, und wo meldet man ihn?

1. Zeit und Symptom festhalten, Logs sichern, nicht alles neu starten.
2. Verdächtige Token widerrufen, bei Verlust Geheimnisse wechseln.
3. Inhaber und Support über die Kontakte **aus dem Vertrag** informieren.
4. Bei Datenleck nach [../compliance/breach-plan.md](../../compliance/breach-plan.md) handeln.

Kontakte, Zeiten und SLA sind in dieser Lieferung nicht gesetzt; sie stehen im Vertrag.
Keine erfundenen Supportadressen verwenden.

---

## Weiterlesen

- [README.md](README.md) — Modellwahl und Basis-URLs.
- [RUNTIME.md](RUNTIME.md) — Installation, Lizenz, Token.
- [EMBEDDED_API.md](EMBEDDED_API.md) — HTTP-Integration.
- [BRIDGE_ONLY.md](BRIDGE_ONLY.md) — Bridge und 10 typische Fehler.
- [COPILOT_SAAS.md](COPILOT_SAAS.md) — Cloud-Kabinett.
- [MANAGEMENT.md](MANAGEMENT.md) — Betrieb und Vorfälle.
