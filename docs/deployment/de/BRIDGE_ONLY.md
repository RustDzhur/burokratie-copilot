# Nur Bridge

**Sprachen:** [Русский](../ru/BRIDGE_ONLY.md) · [Deutsch](BRIDGE_ONLY.md) · [English](../en/BRIDGE_ONLY.md)

**Modell:** Auf dem Rechner des Kunden läuft genau eine Komponente: die **Firmspace
Bridge**. Sie liest Dateien, E-Mails, Datenbankzeilen oder HTTP-Antworten aus der
bestehenden Software und übergibt sie an das Firmspace-Kabinett; Ergebnisse von Aufträgen
kann sie dorthin zurückschreiben. Agenten, OCR und Kategorien sind in dieser Lieferung nicht
enthalten. Die Bridge öffnet keine eingehenden Ports: Sie verbindet sich selbst per HTTPS
mit dem Kabinett und hält eine lokale Warteschlange.

![Weg eines Dokuments vom Ordner bis ELSTER](../../images/document-path.svg)

*Abbildung 2. Die Bridge ist Schritt 2: Sie holt das Dokument beim Kunden ab und gibt es
weiter. Die Schritte 3–5 laufen in Runtime oder Copilot.*

---

## 1. Worum es geht und für wen es gedacht ist

„Nur Bridge“ passt, wenn bereits eine funktionierende Software im Einsatz ist — 1C, DATEV,
Lexware, SAP B1, ein eigenes Programm, eine Dateifreigabe, ein Postfach oder eine
Oracle-/Firebird-Datenbank — und diese nicht geändert werden kann oder soll. Die Bridge
bringt die Daten in das gemeinsame Modell „Dokument / Rechnung / Kontakt / Aufgabe“ und
übergibt sie an das Kabinett. Sie erkennt keine Scans, vergibt keine Kategorien und startet
keine Agenten: Sie ist Transport und Normalisierung. Wer nach der Installation erwartet,
dass „das Dokument sich von selbst einsortiert“, braucht ein anderes Modell — siehe
[Robots Runtime](RUNTIME.md) oder [Copilot als Service](COPILOT_SAAS.md).

---

## 2. Was man braucht

### 2.1 Rechner und Software

| Was | Anforderung | Prüfung |
|---|---|---|
| Betriebssystem | Linux x86_64/arm64, macOS oder Windows 10/11 mit Docker | `uname -a` / `winver` |
| Docker Engine | 24 oder neuer | `docker version --format '{{.Server.Version}}'` |
| Architektur | `amd64` oder `arm64` | `uname -m` |
| Arbeitsspeicher | 512 MB plus Reserve für Dateien | `docker info` |
| Speicherplatz | 2 GB plus Platz für die Warteschlange | `df -h` |
| Uhrzeit | NTP, Abweichung unter 5 Minuten | `timedatectl status` |
| Netzwerk | **ausgehend** HTTPS 443 zu `agents.firmspace.de` | `curl -I https://agents.firmspace.de` |
| Ordner | UID/GID 1000 im Container muss lesen (und schreiben, wenn nötig) | `ls -ln <Ordner>` |

Für den Betrieb ohne Docker wird zusätzlich **Node.js 22 LTS** benötigt: `node -v` muss
`v22.x` zeigen. Für RPA und UNC-Freigaben ist die Windows-Variante nötig, kein
Linux-Container.

### 2.2 Was im Kabinett vorbereitet wird

| Was | Wer liefert es | Prüfung |
|---|---|---|
| Bridge-Token und `bridge.config.json` | Roboter-Kabinett | Datei heruntergeladen, Token enthalten |
| Name der Bridge | Inhaber | z. B. „Buchhaltung – Büro“ |
| Parameter der Adapter | IT des Kunden | Pfade, Postfach, Datenbank nur lesend |
| Testdateien | Inhaber | 2–3 unkritische Dokumente |

### 2.3 Basis-URL und Token

**Die Basis-URL der Bridge ist die Adresse des Roboter-Kabinetts:**
`https://agents.firmspace.de`. Die Bridge verbindet sich dorthin ausgehend über HTTPS; eine
eigene „Bridge-API“ wird nicht nach außen veröffentlicht.

**Der Bridge-Token** wird beim Anlegen der Bridge genau einmal ausgegeben:

1. `https://agents.firmspace.de` öffnen und mit dem Konto des Unternehmensinhabers anmelden.
2. **Unternehmen** öffnen und das richtige Unternehmen wählen.
3. **Verbindungen** öffnen.
4. **Bridge hinzufügen** anklicken.
5. Namen und unkritische Parameter der Adapter eintragen: Ordner, Post, Datenbank, HTTP,
   Druck, CSV/XML. Passwörter und Schlüssel werden **nicht** im Kabinett eingetragen; sie
   bleiben auf dem Rechner des Kunden.
6. Speichern und **sofort** `bridge.config.json` herunterladen. Der Token steht in der Datei
   und wird nur einmal angezeigt.
7. Ist die Datei verloren: Bridge im Kabinett widerrufen, eine neue anlegen und die neue
   Datei herunterladen. Der alte Token lässt sich nicht wiederherstellen.

`[SCREENSHOT]` Bildschirm „Verbindungen → Bridge hinzufügen“ und der Download-Dialog für
`bridge.config.json` — in der Kabinett-Version aufnehmen, die beim Kunden läuft.

> **Token nicht verwechseln.** Der Bridge-Token ist kein Copilot-API-Token und kein
> Runtime-Admin-Token. Er gilt nur für das Roboter-Kabinett und liegt in `.env` als
> `BRIDGE_TOKEN`.

---

## 3. Installation mit Docker

### 3.1 Woher das Image-Archiv kommt

Eine öffentliche Image-Registry gibt es nicht. Das Archiv wird mit der Lieferung übergeben:
als Download-Link, über einen Dateiaustausch oder auf einem Datenträger. Der Dateiname
enthält Version und Architektur, zum Beispiel `firmspace-bridge-<Version>-amd64.tar.gz`
oder `firmspace-bridge-<Version>-arm64.tar.gz`. Den genauen Namen und die Prüfsumme nennt
Firmspace; laden Sie Images nicht aus beliebigen Quellen.

`[SCREENSHOT]` Seite oder E-Mail mit dem Download des Archivs, falls dieser Weg genutzt wird.

Prüfen Sie die Datei vor dem Laden:

```bash
ls -lh firmspace-bridge-*.tar.gz
shasum -a 256 firmspace-bridge-*.tar.gz      # macOS
sha256sum firmspace-bridge-*.tar.gz          # Linux
```

Vergleichen Sie die Summe mit der im Abnahmeprotokoll oder in der E-Mail. Fehlt sie, fordern
Sie sie an.

### 3.2 Architektur feststellen

```bash
uname -m
# x86_64        -> amd64
# aarch64/arm64 -> arm64
```

### 3.3 Image laden

```bash
docker load -i firmspace-bridge-<Version>-<Architektur>.tar.gz
```

Erwartete Ausgabe: eine Zeile `Loaded image: firmspace-bridge:<Version>`.

Prüfen Sie, dass wirklich die passende Architektur geladen wurde:

```bash
docker image inspect firmspace-bridge:0.1.0 \
  --format '{{.Os}}/{{.Architecture}}'
# linux/amd64  oder  linux/arm64
```

Passt die Architektur nicht zu `uname -m`, startet das Image nicht. Der Dateiname ist kein
Beweis; der Beweis ist `docker image inspect`.

### 3.4 Dateien ablegen

```bash
sudo mkdir -p /opt/firmspace-bridge/config /opt/firmspace-bridge/data
sudo chown -R 1000:1000 /opt/firmspace-bridge
cd /opt/firmspace-bridge
```

Kopieren Sie die heruntergeladene `bridge.config.json` nach `config/`:

```bash
cp ~/Downloads/bridge.config.json ./config/bridge.config.download.json
ls -l ./config/
```

### 3.5 Konfiguration importieren

Der Import bringt die Datei aus dem Kabinett in das Bridge-Format, verschiebt den Token in
die `.env` und lässt im Konfigurationsfile nur `${BRIDGE_TOKEN}` stehen:

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  firmspace-bridge:0.1.0 \
  import --from /data/bridge.config.download.json \
         --out /data/bridge.config.json \
         --force
```

Erwartet wird eine Meldung über den Import und eine Liste der Punkte, die von Hand geklärt
werden müssen (SQL-Abfrage, RPA-Szenario, Spaltenzuordnung). Prüfen Sie danach:

```bash
ls -l config/bridge.config.json config/.env
chmod 600 config/.env
grep -c 'BRIDGE_TOKEN' config/.env
```

In `config/.env` muss `BRIDGE_TOKEN=...` stehen. In `config/bridge.config.json` darf kein
echter Token stehen, nur `${BRIDGE_TOKEN}`. Steht dort noch ein Token, entfernen und den
Import wiederholen.

Weitere Geheimnisse (Postpasswort, Datenbank, API-Token des Altsystems) bleiben als
Platzhalter stehen. Füllen Sie sie in `config/.env` aus:

```bash
sudo editor config/.env
chmod 600 config/.env
```

### 3.6 Vor dem Start prüfen

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  -v "$PWD/data:/data/data" \
  firmspace-bridge:0.1.0 \
  test --config /data/bridge.config.json --details
```

Erwartet wird für jeden benötigten Adapter ein `ok` und bei `ok=false` eine klare Ursache.
Rückgabecode `0` bedeutet Erfolg, `1` einen Fehler.

Umgebungsdiagnose:

```bash
docker run --rm -it \
  -v "$PWD/config:/data" \
  firmspace-bridge:0.1.0 \
  doctor --config /data/bridge.config.json
```

`doctor` prüft Node, Konfiguration, Token, TLS, Rechte, Speicherplatz und Adapter. Das ist
der erste Befehl bei jedem Problem.

### 3.7 Bridge starten

```bash
docker run -d --name firmspace-bridge \
  --restart unless-stopped \
  -v "$PWD/config:/data" \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  -v /srv/legacy/outgoing:/data/data/outgoing \
  --env-file config/.env \
  firmspace-bridge:0.1.0
```

Bedeutung der Parameter:

| Parameter | Zweck |
|---|---|
| `-d` | im Hintergrund starten |
| `--restart unless-stopped` | startet nach einem Neustart wieder |
| `-v "$PWD/config:/data"` | Konfiguration und `.env` im Container |
| `-v firmspace-bridge-state:/data/.bridge` | Warteschlange, Protokoll und Zustand überleben ein Neu-Anlegen des Containers |
| `-v /srv/legacy/incoming:/data/data/incoming` | Kundenordner; der Pfad **im Container** muss zur Konfiguration passen |
| `--env-file config/.env` | `BRIDGE_TOKEN` und Adapter-Geheimnisse |

Prüfen Sie, ob der Container läuft und die Bridge das Kabinett sieht:

```bash
docker ps --filter name=firmspace-bridge
docker logs --tail=50 firmspace-bridge
docker exec firmspace-bridge \
  node dist/cli/index.js status --config /data/bridge.config.json --json
```

In `status` müssen `lastHeartbeatOk=true`, eine steigende `uptime` und eine leere oder kleine
`queue.total` stehen. Im Kabinett erscheint die Bridge in der Liste der Verbindungen mit
Status „online“ und der Zeit des letzten Heartbeats.

`[SCREENSHOT]` Karte der Bridge im Kabinett: Status, Heartbeat, Warteschlange.

---

## 4. Wohin die Dateien gehören

| Was | Auf dem Host | Im Container |
|---|---|---|
| Konfiguration | `/opt/firmspace-bridge/config/bridge.config.json` | `/data/bridge.config.json` |
| Geheimnisse | `/opt/firmspace-bridge/config/.env` (600) | `/data/.env` |
| Warteschlange, Protokoll, Lock | Volume `firmspace-bridge-state` | `/data/.bridge` |
| Eingangsordner des Kunden | `/srv/legacy/incoming` | `/data/data/incoming` |
| Ausgangsordner des Kunden | `/srv/legacy/outgoing` | `/data/data/outgoing` |
| Druck-Spool (falls genutzt) | `/srv/legacy/spool` | `/data/data/spool` |

Regel: **In der Konfiguration stehen Container-Pfade**, keine Host-Pfade. Wurde im Kabinett
`/srv/legacy/incoming` angegeben, prüfen Sie nach dem Import, dass in
`bridge.config.json` `data/incoming` oder `/data/data/incoming` steht.

Rechte: Der Container läuft als `node` mit UID/GID 1000. Gehört der Ordner einem anderen
Benutzer, kann die Bridge ihn nicht lesen:

```bash
ls -ln /srv/legacy/incoming
sudo chown -R 1000:1000 /srv/legacy/incoming
```

---

## 5. Was im Kabinett erscheint

1. Die Bridge in der Liste der Verbindungen des Unternehmens: Name, Version, Plattform,
   letzter Heartbeat.
2. Adapter mit Zustand `running` / `error` und Zeit des letzten Ereignisses.
3. Warteschlange und Aufträge: `ingest`, `export`, `run-agent`, `test-connection`.
4. Ereignis- und Ergebnisprotokoll; Fehler stehen im Kabinett und im lokalen Protokoll
   `.bridge/journal/YYYY-MM-DD.jsonl`.
5. Aufträge, die die Bridge aus dem Kabinett holt, und Ergebnisse, die sie dorthin schickt.

Ist die Bridge nach 2–3 Minuten nicht im Kabinett sichtbar, mit §7 beginnen.

---

## 6. Aktualisierung und Sicherung

### 6.1 Aktualisierung

```bash
# 1. Neues Archiv laden und Prüfsumme vergleichen
docker load -i firmspace-bridge-<neue-Version>-<Architektur>.tar.gz

# 2. Alten Container stoppen und entfernen, Zustands-Volume behalten
docker stop firmspace-bridge
docker rm firmspace-bridge

# 3. Neues Image mit denselben Volumes starten
docker run -d --name firmspace-bridge \
  --restart unless-stopped \
  -v /opt/firmspace-bridge/config:/data \
  -v firmspace-bridge-state:/data/.bridge \
  -v /srv/legacy/incoming:/data/data/incoming \
  -v /srv/legacy/outgoing:/data/data/outgoing \
  --env-file /opt/firmspace-bridge/config/.env \
  firmspace-bridge:<neue-Version>
```

Lesen Sie vor dem Update die Versionshinweise: Das Konfigurationsformat kann sich ändern.
Scheitert `bridge test` nach dem Update mit `CONFIG_INVALID`, auf das vorige Image
zurückgehen und den Support informieren.

### 6.2 Sicherung

```bash
cd /opt/firmspace-bridge
tar czf "bridge-backup-$(date +%F).tgz" \
  config/bridge.config.json config/.env
docker run --rm -v firmspace-bridge-state:/src -v "$PWD:/backup" \
  alpine tar czf /backup/bridge-state-$(date +%F).tgz -C /src .
```

`config/.env` enthält Token und Passwörter. Verschlüsseln Sie die Kopie, bevor sie den
Rechner verlässt; nicht auf ein gemeinsames Laufwerk legen und nicht in ein Ticket hängen.

---

## 7. Wenn es nicht funktioniert: 10 typische Fehler

Zuerst immer diese drei Befehle:

```bash
docker ps -a --filter name=firmspace-bridge
docker logs --tail=100 firmspace-bridge
docker exec firmspace-bridge node dist/cli/index.js doctor --config /data/bridge.config.json
```

### 7.1 `docker load`: Datei nicht gefunden oder „invalid tar header“

**Ursache:** falsche Datei, beschädigtes Archiv oder kein `docker save`.
**Diagnose:**

```bash
file firmspace-bridge-*.tar.gz
shasum -a 256 firmspace-bridge-*.tar.gz
docker load -i firmspace-bridge-*.tar.gz
```

**Was tun:** erneut herunterladen und die Prüfsumme vergleichen. Das Archiv nicht von Hand
entpacken und `.tar` nicht in `.tar.gz` umbenennen.

### 7.2 Container beendet sich sofort (`Exited (1)`)

**Ursache:** Konfiguration fehlt, Pfad falsch oder JSON ungültig.
**Diagnose:**

```bash
docker logs firmspace-bridge
docker run --rm -it -v /opt/firmspace-bridge/config:/data \
  firmspace-bridge:0.1.0 test --config /data/bridge.config.json --details
```

**Was tun:** prüfen, dass `/data/bridge.config.json` existiert und gültiges JSON ist. Die
Meldung `CONFIG_INVALID` nennt das betroffene Feld.

### 7.3 `CONFIG_INVALID`

**Ursache:** Tippfehler im Schlüssel, falscher Typ, unbekanntes Feld (das Schema lehnt
zusätzliche Schlüssel ab) oder ein nicht ersetzter Platzhalter.
**Diagnose:**

```bash
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

**Was tun:** das genannte Feld in `bridge.config.json` korrigieren. Keine Schlüssel „auf
Vorrat“ ergänzen; das zod-Schema akzeptiert sie nicht. Danach `docker restart
firmspace-bridge`.

### 7.4 `ALREADY_RUNNING`

**Ursache:** Ein anderer Prozess nutzt dasselbe `.bridge`, oder ein Lock eines beendeten
Prozesses ist übrig.
**Diagnose:**

```bash
docker ps -a --filter name=firmspace-bridge
docker exec firmspace-bridge ls -l /data/.bridge/bridge.lock
ps aux | grep -i bridge
```

**Was tun:** den zweiten Prozess beenden. Läuft keiner mehr und ist nur das Lock übrig,
ausschließlich das Lock entfernen:

```bash
docker exec firmspace-bridge rm -f /data/.bridge/bridge.lock
docker restart firmspace-bridge
```

Das Verzeichnis `.bridge` nicht komplett löschen: Dort liegen Warteschlange und Protokoll.

### 7.5 `CONSOLE_AUTH_FAILED` (HTTP 401)

**Ursache:** Token falsch, widerrufen oder in `.env` steht noch ein Platzhalter.
**Diagnose:**

```bash
docker exec firmspace-bridge sh -c 'grep -c "^BRIDGE_TOKEN=." /data/.env'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
docker exec firmspace-bridge node dist/cli/index.js \
  doctor --config /data/bridge.config.json
```

`grep -c` muss `1` liefern. Zeigen `doctor`/`test` `CONSOLE_AUTH_FAILED`, wird der Token
nicht akzeptiert.
**Was tun:** Bridge im Kabinett widerrufen, neu anlegen, neue `bridge.config.json`
herunterladen, Import wiederholen, Container neu starten. Der alte Token ist nicht
wiederherstellbar.

### 7.6 `CONSOLE_UNREACHABLE`

**Ursache:** DNS, Proxy, Firewall oder falsche `console.url`.
**Diagnose:**

```bash
docker exec firmspace-bridge sh -c 'getent hosts agents.firmspace.de || nslookup agents.firmspace.de'
curl -vI https://agents.firmspace.de
docker exec firmspace-bridge node dist/cli/index.js \
  status --config /data/bridge.config.json --json
```

**Was tun:** ausgehenden Port 443, Proxy-Einstellungen (`HTTPS_PROXY`) und
`console.url` prüfen. Die Bridge arbeitet mit der lokalen Warteschlange weiter, wenn das
Kabinett nicht erreichbar ist, erhält aber keine neuen Aufträge, bis die Verbindung steht.

### 7.7 `CONSOLE_TLS_ERROR`

**Ursache:** Das Unternehmens-CA ist im Container nicht vertraut, das Zertifikat ist
abgelaufen oder der Name passt nicht.
**Diagnose:**

```bash
openssl s_client -connect agents.firmspace.de:443 \
  -servername agents.firmspace.de -showcerts </dev/null
```

**Was tun:** das Unternehmens-CA als Datei ablegen und in der Konfiguration eintragen:

```jsonc
"console": {
  "url": "https://agents.firmspace.de",
  "tls": { "rejectUnauthorized": true, "caFile": "/data/ca.pem" }
}
```

Die Datei in den Container mounten und neu starten. `rejectUnauthorized` in Produktion
nicht abschalten.

### 7.8 `PATH_NOT_ALLOWED` oder Dateien werden nicht abgeholt

**Ursache:** Ein Pfad im Auftrag liegt außerhalb von `allowedRoots`, der Host-Pfad ist nicht
gemountet, UID 1000 fehlt das Recht, oder die Datei wird noch geschrieben
(`stabilityMs`).
**Diagnose:**

```bash
docker exec firmspace-bridge ls -ln /data/data/incoming
docker inspect firmspace-bridge --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --details
```

**Was tun:** den Ordner in `security.allowedRoots` aufnehmen, prüfen, dass der Pfad in der
Konfiguration ein Container-Pfad ist, und UID 1000 Lesen/Schreiben geben:

```bash
sudo chown -R 1000:1000 /srv/legacy/incoming
```

### 7.9 `EMAIL_AUTH_FAILED` / `EMAIL_CONNECTION_FAILED`

**Ursache:** normales Passwort statt App-Passwort, 2FA, falscher Port/TLS oder abgelaufener
OAuth2-Token.
**Diagnose:**

```bash
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --adapter <id> --details
openssl s_client -connect imap.example.de:993 -servername imap.example.de </dev/null
```

**Was tun:** bei 993 `secure: true`; bei 587 `secure: false` und `requireTls: true`; bei
Gmail App-Passwort oder OAuth2. Anmeldung im Web-Postfach prüfen und sicherstellen, dass
IMAP aktiv ist.

### 7.10 `DB_CONNECT_FAILED` / `ODBC_DRIVER_MISSING`

**Ursache:** keine Verbindung zur Datenbank, falsche Anmeldung, fehlender Treiber oder der
Read-Guard hat die Abfrage abgelehnt.
**Diagnose:**

```bash
docker exec firmspace-bridge node -e '
const net = require("net");
const s = net.connect(5432, "db.example.de", () => { console.log("open"); s.end(); });
s.on("error", (e) => console.log("ERR", e.code));
'
docker exec firmspace-bridge node dist/cli/index.js \
  test --config /data/bridge.config.json --adapter <id> --details
```

Erwartet wird `open`; `ERR ETIMEDOUT` oder `ECONNREFUSED` bedeutet Netzwerk/Firewall. Bei
Oracle/MSSQL den ODBC-Treiber prüfen; für PostgreSQL/MySQL ist der Treiber im Image.
**Was tun:** den Datenbank-Port für den Bridge-Rechner öffnen, einen Benutzer **nur mit
Leserechten** verwenden, DML/DDL aus der Abfrage entfernen (Read-Guard) und auf dem Host
`odbcinst -q -d` prüfen.

> Steht der Fehler nicht in dieser Liste: `doctor` und `status --json` ausführen, das
> `.bridge/journal` der letzten 24 Stunden sichern und an den Support geben. Keine `.env`
> und keine `bridge.config.json` mit Geheimnissen mitsenden.

---

## 8. Sicherheit und Grenzen

- Die Bridge öffnet keine eingehenden Ports. Alle Verbindungen gehen ausgehend über HTTPS 443.
- Im Kabinett liegt nur der SHA-256 des Bridge-Tokens; der Token selbst in `.env` mit
  Rechten 600.
- Adapter-Passwörter bleiben auf dem Rechner des Kunden; im Kabinett stehen nur unkritische
  Parameter.
- Standardmäßig read-only: Hintergrundschreibvorgänge sind verboten; Schreiben nur über
  Aufträge und nur bei `security.allowJobWrites=true`.
- `allowedRoots` verhindert, dass ein Auftrag die erlaubten Verzeichnisse verlässt; Symlinks
  umgehen die Allowlist nicht.
- Die Zustellung ist **at-least-once**. Adapter, die nach außen schreiben, müssen idempotent
  sein.
- Die Bridge kann kein OCR, keine Kategorien, keine Agenten, keine Oberfläche und keine
  ELSTER-Ausgabe. Das ist nicht ihre Aufgabe.

---

## 9. Abnahme-Checkliste

- [ ] Image-Architektur passt zu `uname -m` (`docker image inspect`).
- [ ] `bridge.config.json` importiert; kein echter Token darin.
- [ ] `.env` vorhanden, Rechte `600`, `BRIDGE_TOKEN` gefüllt.
- [ ] `bridge test --details` — alle benötigten Adapter `ok`.
- [ ] `bridge doctor` — keine kritischen Fehler.
- [ ] Container läuft mit `--restart unless-stopped`.
- [ ] Bridge im Kabinett „online“, Heartbeat aktuell.
- [ ] Testdatei hat den Weg genommen: Ordner → Ereignis → Kabinett.
- [ ] Sicherung von Konfiguration und `.env` erstellt und verschlüsselt.
- [ ] Verantwortlicher und Update-Ablauf festgelegt.
