# Copilot als Service (Cloud)

**Sprachen:** [Русский](../ru/COPILOT_SAAS.md) · [Deutsch](COPILOT_SAAS.md) · [English](../en/COPILOT_SAAS.md)

**Modell:** ein fertiges Cloud-Kabinett von Firmspace Copilot. Der Kunde stellt keine
Server auf, wartet kein PostgreSQL und aktualisiert keine Container: Er bekommt Zugriff über
HTTPS, richtet die Eingangskanäle für Dokumente ein und arbeitet im Browser.

![Die vier Bereitstellungsmodelle](../../images/deployment-models.svg)

*Abbildung 1. Copilot als Service ist das vierte Modell: Daten und Engine in der Cloud, beim
Kunden nur der Browser.*

---

## 1. Worum es geht und für wen es gedacht ist

Copilot als Service ist die Miete eines fertigen Kabinetts: Dokumente, OCR, Kategorien,
Aufgaben, Agenten, Ausgaben und Audit funktionieren bereits, ohne Server beim Kunden. Die
Verwaltung läuft im Browser unter `https://burokratie.firmspace.de`; für Integrationen gibt
es denselben Host mit dem Präfix `/api/v1`. Das Modell passt zu kleinen und mittleren
Unternehmen, Steuerkanzleien und Teams, die schnell starten wollen und die Verarbeitung von
Daten in der EU bei Firmspace zulassen. Dürfen Dokumente den Kundenbereich nicht verlassen,
ist [Robots Runtime](RUNTIME.md) das richtige Modell.

---

## 2. Was man braucht

| Was | Anforderung | Prüfung |
|---|---|---|
| Browser | aktuelles Chrome, Firefox, Edge oder Safari | `https://burokratie.firmspace.de` öffnen |
| Internet | ausgehend HTTPS 443, kein Air-Gap | `curl -I https://burokratie.firmspace.de` |
| E-Mail | Zugriff auf das Postfach für Bestätigung und Einladungen | Nachricht von Firmspace |
| Passwort | 12–128 Zeichen, nicht aus der Liste häufiger Passwörter | bei der Registrierung |
| Uhrzeit | NTP, Abweichung unter 5 Minuten | `timedatectl status` |
| `curl` | 7.76+, wenn die API geprüft wird | `curl --version` |

Die Selbstregistrierung kann auf einer Instanz geschlossen sein. Ist die Schaltfläche
„Registrierung“ nicht vorhanden oder liefert einen Fehler, legt ein Firmspace-Administrator
das Konto an — bei Unternehmenslieferungen ist das normal.

---

## 3. Basis-URL

| Was | Adresse |
|---|---|
| Kabinett | `https://burokratie.firmspace.de` |
| API | `https://burokratie.firmspace.de/api/v1` |
| Health | `https://burokratie.firmspace.de/actuator/health/readiness` |
| Lizenzstatus der Instanz | `https://burokratie.firmspace.de/api/v1/license` |

`BASE_URL` in den Beispielen ist `https://burokratie.firmspace.de` **ohne** `/api/v1`; die
Pfade werden pro Aufruf ergänzt. Prüfung:

```bash
export BASE_URL="https://burokratie.firmspace.de"
curl -fsS "$BASE_URL/actuator/health/readiness"
curl -fsS "$BASE_URL/api/v1/license" | jq '{state, plan}'
```

Liefert `curl` bei `/api/v1/license` ein `404`, prüfen Sie, ob `/api/v1` doppelt im Pfad
steht und ob ein Proxy den Pfad abschneidet.

---

## 4. Registrierung und erste Anmeldung

1. `https://burokratie.firmspace.de` öffnen.
2. **Registrierung** anklicken (Adresse `/register`).
3. Ausfüllen: E-Mail, Passwort (12–128 Zeichen), Name, Kontotyp, Firmenname, Sprache und
   Zeitzone.
4. E-Mail über den Link in der Nachricht bestätigen. Ohne Bestätigung ist die Anmeldung
   gesperrt.
5. Anmelden (`/login`). Beim ersten Login kann eine kurze Einrichtung erscheinen.

Kontotypen: Privatperson, Familie, Freiberufler/Einzelunternehmer, Unternehmen, Team,
externer Vertreter, Organisation mit erhöhten Anforderungen. Der Typ beeinflusst Kabinett
und Tarif, nicht die API.

`[SCREENSHOT]` Registrierungsbildschirm und Bestätigungs-E-Mail.

### 4.1 Rollen

| Rolle | Darf | Für wen |
|---|---|---|
| `OWNER` | alles, inklusive Eigentumsübergabe und Löschung | Inhaber |
| `ADMIN` | alles außer Übergabe/Löschung | Administrator |
| `MANAGER` | Vorgänge, Aufgaben, Dokumente, Finanzen, Freigaben | Führungskraft |
| `EMPLOYEE` | zugewiesene Vorgänge, Dokumente, Aufgaben | Mitarbeitende |
| `ACCOUNTANT` | Finanzen, Dokumente, Ausgaben | Buchhaltung |
| `LEGAL_ADVISOR` | Verträge und rechtliche Vorgänge | Jurist |
| `EXTERNAL_ADVISOR` | nur zugewiesene Vorgänge | externer Berater |
| `AUDITOR` | Protokolle und Nachweise ohne Änderung | Audit |
| `READ_ONLY` | nur Lesen | Beobachter |

Rechte werden auf dem Server geprüft. Eingeladene erhalten Zugriff erst nach Annahme; eine
Person kann in mehreren Organisationen sein, sieht aber nur die, in denen sie Mitglied ist.

---

## 5. Dokumente: wie sie ins Kabinett kommen

### 5.1 Kanäle

| Kanal | Wo im Interface | Wann |
|---|---|---|
| Manueller Upload | **Scannen und Import** → Dateien hineinziehen | einmalig |
| Dokumentenregister | **Dokumentenregister** → Liste und Karte | Ansicht/Suche |
| E-Mail | **Postzentrum** → Postfach verbinden | regelmäßig |
| Scan/MFU | Ordner oder Bridge | regelmäßig |
| API | `POST /api/v1/documents:ingest` | Integration |
| Bridge | Roboter-Kabinett → Verbindung | bei Altsystemen |

### 5.2 Upload im Browser

1. Im linken Menü **Scannen und Import** öffnen.
2. PDF/Bild in den Upload-Bereich ziehen oder eine Datei auswählen.
3. Warten, bis „Gespeichert. Das Dokument wird jetzt gelesen“ erscheint.
4. **Dokumentenregister** öffnen: Das Dokument steht in der Liste.
5. Ist sich das System bei Typ oder Feldern unsicher, landet es im **Prüfzentrum** —
   bestätigen oder korrigieren. Das ist ein normaler Ablauf, kein Fehler.

`[SCREENSHOT]` Upload-Bereich und Dokumentkarte mit Verarbeitungsstatus.

Standardlimits: Datei bis 100 MB, Anfrage bis 250 MB. Duplikate werden über SHA-256 erkannt:
Ein erneuter Upload derselben Datei erzeugt kein zweites Dokument.

### 5.3 Was danach passiert

1. Prüfung von Typ, Größe und Signatur; Antivirus, falls aktiviert.
2. Textextraktion: digitales PDF direkt, Scan über OCR.
3. Erkennung von Typ und Feldern; bei geringer Sicherheit prüft ein Mensch.
4. Kategorie, Ablage, Frist, Aufgabe; alles wird protokolliert.
5. Unklare Dokumente gehen ins **Prüfzentrum**, die Entscheidung wird gespeichert.

Offen gesagt: OCR irrt bei schlechten Scans, Handschrift und ungewöhnlichen Tabellen. Die
Plattform zeigt die Sicherheit und schickt zur Prüfung; „100 % automatisch“ wird nicht
versprochen. KI-Funktionen können abgeschaltet sein — dann arbeiten OCR und Regeln, aber
keine KI-Erklärungen.

---

## 6. Woher der API-Token kommt

Ein Token wird gebraucht, wenn ein externes System (eigenes Produkt, Scanner, Bridge,
Skript) Dokumente liefern oder Agenten starten soll.

1. Bei `https://burokratie.firmspace.de` anmelden.
2. Kontomenü oben rechts → **Kontoeinstellungen**.
3. Karte **API-Tokens** wählen.
4. **Token anlegen** anklicken.
5. **Namen** vergeben (z. B. `embedded-prod`).
6. Rechte anhaken:
   - **Dokumente einliefern** (`documents:write`) — für den Upload;
   - **Dokumente lesen** (`documents:read`) — wenn gelesen werden soll;
   - **Agenten ansehen** (`agents.view`) — Katalog und Protokolle;
   - **Agenten ausführen** (`agents.run`) — Start;
   - `exports:read` — für die ELSTER-Ausgabe (kann als technisches Kürzel erscheinen).
7. **Token erstellen** anklicken.
8. Im Dialog **Token angelegt** auf **Token kopieren** klicken und den Wert speichern.

Der Wert wird **einmal** angezeigt. In der Liste stehen Name, `prefix`, Rechte,
Erstellungsdatum, letzte Verwendung und Status; der Token selbst nicht.

`[SCREENSHOT]` Karte „API-Tokens“, Anlage-Dialog, Dialog „Token angelegt“.

Ist der Token verloren: **API-Tokens** → Eintrag am Namen erkennen → **Widerrufen** → neu
anlegen. Eine Wiederherstellung ist unmöglich; der Server kennt nur den SHA-256.

Grenzen: höchstens 20 aktive Token pro Workspace; standardmäßig 60 Anfragen pro Minute;
Rechte nach dem Anlegen unveränderlich; anlegen darf eine Rolle mit `api_tokens.manage`.

![Woher der Token kommt](../../images/token-locations.svg)

*Abbildung 3. Drei verschiedene Token: Copilot-API, Bridge, Runtime-Admin. Sie sind nicht
austauschbar.*

---

## 7. ELSTER-Ausgabe

1. **Berichte und Analyse** öffnen.
2. Den Block **Ausgaben und ELSTER-Zuordnung** suchen.
3. Das Jahr im Jahresfeld eintragen.
4. **CSV exportieren** anklicken — die Datei `elster-<Jahr>.csv` wird heruntergeladen.

Tabelle und CSV enthalten dieselben Zeilen: Bildschirm und Datei können keine
unterschiedlichen Geschichten erzählen. Ein Dokument ohne Kategorie, Zeile oder Datum wird
nicht versteckt, sondern mit Warnung angezeigt.

`[SCREENSHOT]` Block „Ausgaben und ELSTER-Zuordnung“ mit Summen und Exportknopf.

CSV-Format: Trennzeichen `;`, Dezimalkomma, fester Kopf:

```text
category_code;euer_line;euer_kz;datev_skr03;datev_skr04;booking_kind;netto;ust;brutto;vorsteuer_abziehbar;bewirtung_abziehbar_70;bewirtung_nicht_abziehbar_30;document_id;date
```

Das ist keine Steuerberatung: Vor der Abgabe prüft die Buchhaltung Regeln und Kategorien.
Mehr in [../ELSTER_README.md](../../ELSTER_README.md).

---

## 8. Daten, Aufbewahrung, Tarif

### 8.1 Wohin die Daten gehen

Dokumente, Metadaten und Protokolle liegen in der Cloud-Instanz von Firmspace. Die Region
richtet sich nach dem Vertrag, Zielregion ist die EU. Nach außen gehen Daten nur über
aktivierte Kanäle: E-Mail, externer KI-Anbieter, Integrationen. Liste der Subunternehmer und
Schutzmaßnahmen: [../compliance/README.md](../../compliance/README.md),
[../compliance/subprocessors.md](../../compliance/subprocessors.md),
[../security.md](../../security.md).

### 8.2 Fristen

| Was | Standardwert | Wo änderbar |
|---|---|---|
| Testphase | 60 Tage | `billing.trial_days` |
| Aufbewahrung nach Ende | 100 Tage | `billing.retention_days` |
| Aufbewahrung der Dokumente im Tarif | laut Vertrag | Tarif/Einstellungen |

Nach der Testphase wird der Zugriff eingeschränkt; Daten bleiben für die genannte Frist
gespeichert und werden danach gelöscht, sofern nichts anderes vereinbart ist. Genaue Fristen
für einen Kunden stehen im Vertrag, nicht in dieser Tabelle.

### 8.3 Tarif

Tarife und Preise sind eine kaufmännische Entscheidung, keine technische Konstante. In der
Lieferung gibt es Startwerte in Migrationen; öffentliche Angebote und SLA werden vertraglich
geregelt. Versprechen Sie keinen konkreten Preis, bevor er feststeht.

---

## 9. Sicherheit und Grenzen

- TLS ist Pflicht; Zertifikatsprüfung nicht abschalten.
- Passwort 12–128 Zeichen, nicht aus der Liste häufiger Passwörter.
- Anmeldung kann durch einen zweiten Faktor (MFA) geschützt werden, wenn die Instanz ihn
  anbietet.
- Token nur für serverseitige Integrationen; nicht an Browser oder mobile Apps geben.
- Sitzungen laufen ab; nach einem Passwortwechsel können alte Sitzungen beendet werden.
- Export und Löschung über die Plattform, nicht über SQL.
- Kein Betrieb ohne Internet, kein Air-Gap, kein Zugriff auf Server/DB, kein „eigener
  KI-Anbieter per Klick“.
- Keine direkte DATEV-API und keine rechtliche/steuerliche Beratung.

---

## 10. Wenn es nicht funktioniert

| Symptom | Ursache | Was tun |
|---|---|---|
| Keine Bestätigungs-E-Mail | Spam, falsche Adresse, geschlossene Registrierung | Spam prüfen, erneut senden, Administrator fragen |
| Anmeldung nach Registrierung abgelehnt | E-Mail nicht bestätigt | Link in der Nachricht öffnen |
| „Kein Zugriff“ bei API-Tokens | Recht `api_tokens.manage` fehlt | OWNER/ADMIN um Rolle bitten |
| Token-Anlage „20 aktive“ | Workspace-Limit | unnötigen Token widerrufen |
| Upload startet nicht | Datei > 100 MB oder Typ gesperrt | Datei verkleinern; Format prüfen |
| Dokument „wird gelesen“ zu lange | OCR-Warteschlange/Last | warten, Status prüfen, Support mit `documentId` fragen |
| ELSTER-Ausgabe leer | keine Dokumente im Jahr oder keine Kategorien | Jahr und Kategorien prüfen; leeres Jahr ist `200`, kein Fehler |
| API `401` | Token falsch/widerrufen | neuen Token anlegen |
| API `403` | Scope fehlt | neuen Token mit passendem Scope |
| API `402` | Tarif/Plan | Tarif prüfen, Inhaber fragen |

---

## 11. Einführungs-Checkliste

- [ ] Konto erstellt, E-Mail bestätigt, Rollen vergeben.
- [ ] Eingangskanäle für Dokumente festgelegt.
- [ ] Testdokument hochgeladen und im Register sichtbar.
- [ ] Prüfzentrum an einem unklaren Dokument getestet.
- [ ] Für jede Integration ein eigener API-Token.
- [ ] Token im Secret Store, nicht im Ticket.
- [ ] ELSTER-Ausgabe für ein Testjahr geprüft.
- [ ] Aufbewahrungsfrist und Tarif im Vertrag festgehalten.
- [ ] Verantwortlicher für Konto und Support benannt.
