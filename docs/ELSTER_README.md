# ELSTER-/DATEV-Mapping — Kurzanleitung für die Buchhaltung

Diese Anleitung erklärt, wie die drei Dateien im Alltag benutzt werden. Sie ersetzt keine
Steuerberatung; sie sagt nur, **wo** ein Beleg hingehört und **was** vorher geprüft werden muss.

| Datei | Inhalt | Für wen |
|---|---|---|
| [`ELSTER_MAPPING.md`](ELSTER_MAPPING.md) | Vollständige Doku: Aufbau der Anlage EÜR, alle 112 Kategorien, Sonderfälle, Feldkarte CRM → DATEV → EÜR, Quellen | Buchhaltung, Steuerberater, Entwicklung |
| [`ELSTER_CATEGORIES.csv`](ELSTER_CATEGORIES.csv) | Maschinenlesbare Tabelle, 112 Zeilen, Semikolon-getrennt | Import, Prüfskripte, Excel |
| diese Datei | Arbeitsanweisung in Kurzform | Buchhaltung im Tagesgeschäft |

**Rechtsstand:** Anlage EÜR 2025 (BMF-Schreiben vom 29.08.2025), USt-VA 2025. Bei Abgabe für ein
anderes Jahr die Zeilennummern am aktuellen Vordruck prüfen — 2023 lag z. B. „Waren" in Zeile 26,
2024/2025 in Zeile 27.

---

## 1. Die Tabelle lesen

Jede Zeile in `ELSTER_CATEGORIES.csv` ist eine Kategorie aus dem CRM-Katalog:

| Spalte | Bedeutung |
|---|---|
| `code` | Technischer Schlüssel, z. B. `fahrzeug.kraftstoff` — stabil, nie umbenennen |
| `parent` | Übergeordnete Kategorie (leer bei den 8 Wurzelknoten) |
| `name_de` | Anzeigename im CRM |
| `euer_line` | Zielzeile der Anlage EÜR 2025. `GRUPPE` = Sammelknoten ohne Buchung, `n/a …` = bewusst keine Betriebsausgabe |
| `euer_label` | Amtliche Zeilenbezeichnung samt Kennzahl (Kz) |
| `datev_skr03` / `datev_skr04` | Basiskonto. **Startwert** — der Kontenrahmen des Mandanten kann abweichen |
| `ust_kz` | Kennzahl der Umsatzsteuer-Voranmeldung (`66` = Vorsteuer aus Rechnung, `66*` = nur unter Voraussetzung, `67` = § 13b, `—` = keine USt) |
| `notes` | Fallstricke: AfA, 70 %, 50 €, nicht abziehbar, Reverse Charge |

**Goldene Regel:** `euer_line` ist ein Vorschlag, `euer_label` ist die Wahrheit. Wenn die Nummer
nicht zur Bezeichnung passt, wurde der Vordruck geändert — dann die Bezeichnung im Formular suchen,
nicht die Nummer.

---

## 2. Pflichtangaben pro Beleg

Ohne diese fünf Angaben ist keine Zeile sicher befüllbar:

1. **Belegdatum** (bestimmt das Wirtschaftsjahr).
2. **Bruttobetrag** und **USt-Satz** (19 / 7 / 0 / steuerfrei).
3. **Vorsteuer abziehbar: ja/nein** — nein bei Geschenken über 50 €, Privatentnahmen,
   steuerfreien Eingangsleistungen.
4. **Kategorie** (`code`) — daraus folgen EÜR-Zeile und DATEV-Konto.
5. **Belegnummer/Datei** — für die Nachweispflicht (Bewirtung, Geschenke, AfA).

Zusätzlich, je nach Fall:

| Fall | Zusatzpflicht |
|---|---|
| Bewirtung | Ort, Tag, **Teilnehmer**, **Anlass**; Rechnung beifügen (§ 4 Abs. 5 Satz 1 Nr. 2 EStG) |
| Geschenk | **Empfänger**; Wert ≤ 50 € netto je Empfänger und Jahr |
| Bewirtung/Geschenk | Aufteilung in **abziehbar / nicht abziehbar** (zwei Beträge, auch wenn einer 0,00 € ist) |
| Investition | Anschaffungskosten, Nutzungsdauer, AfA-Beginn → Anlage AVEÜR |
| GWG | Wert ≤ 800 € netto, Verzeichnis bei > 250 € |
| Kfz | betrieblich/privat, 1-%-Regel oder Fahrtenbuch; Bruttolistenpreis |
| Auslandsrechnung | Reverse Charge? EU (Kz 46/47) oder Nicht-EU (Kz 84/85) |
| Privatentnahme | **keine** Betriebsausgabe — nur Entnahme (Zeile 106) |

---

## 3. Vor der Abgabe: sieben Prüfungen

1. **Vollständigkeit.** Alle Belege haben eine Kategorie; keine Zeile steht auf `GRUPPE`
   (Sammelknoten sind nicht buchbar).
2. **Privatentnahmen ausgeschlossen.** Kein Beleg aus `privatentnahme.*` in den Zeilen 24–75.
   Geld-/Warenentnahmen nur in Zeile 106, private Lebenshaltung gar nicht.
3. **Netto/Brutto.** Regelbesteuerer: netto in die Ausgabenzeile, Vorsteuer in Zeile 57.
   Kleinunternehmer (§ 19 UStG): brutto, keine USt-VA. **Das Mandantenmerkmal muss gesetzt sein** —
   fehlt es, ist der Export nicht belastbar.
4. **Bewirtung und Geschenke.** Zwei Beträge je Beleg (abziehbar/nicht abziehbar), Zeilen 62/63.
   Geschenke über 50 €: kein Vorsteuerabzug.
5. **AfA.** Jede Aktivierung hat einen AVEÜR-Eintrag; GWG (≤ 800 €) und Sammelposten
   (250–1.000 €) sind getrennt; digitale Wirtschaftsgüter dürfen mit einem Jahr abgeschrieben
   werden.
6. **Kfz.** Private Nutzung ist eine **Einnahme** (Zeile 20), keine Ausgabe. 1-%-Regel oder
   Fahrtenbuch — nicht beides mischen.
7. **Gegenprobe.** Summe Zeile 75 = Summe aller Ausgabenkategorien **ohne** Privatentnahmen und
   **ohne** aktivierte Zugänge. Weicht die Summe ab, zuerst die Aktivierungen prüfen.

---

## 4. Rote Linien

- **Keine erfundenen Zeilen.** Wenn eine Ausgabe in der EÜR keine eigene Zeile hat (z. B.
  Bankgebühren, Unternehmensberatung), gehört sie in Zeile 60 („Übrige unbeschränkt abziehbare
  Betriebsausgaben"). Nicht in eine ähnlich klingende Zeile pressen.
- **Privatentnahme ist keine Betriebsausgabe.** Auch nicht „ausnahmsweise".
- **Gewerbesteuer ist nicht abziehbar** (Zeile 67, Spalte „nicht abziehbar").
- **Geschenke über 50 €** sind nicht abziehbar — und die Vorsteuer dafür auch nicht.
- **DATEV-Konten sind Startwerte.** Vor dem ersten Export je Mandant gegen den Kontenrahmen des
  Mandanten abgleichen.

---

## 5. Wenn etwas unklar ist

In der Doku sind unsichere Punkte **sichtbar** markiert:

- `prüfen` — fachliche Zuordnung oder Kontonummer ohne belastbare Quelle in diesem Lauf.
- `prüfen (Quelle fehlt)` — es gibt keine amtliche Zeile; die Zuordnung ist eine Entscheidung,
  kein Zitat.

Diese Markierungen sind **kein Fehler**, sondern offene Punkte. Sie stehen gesammelt in
Abschnitt 6 der [`ELSTER_MAPPING.md`](ELSTER_MAPPING.md). Wer eine Markierung auflöst, ändert die
Doku **und** die CSV und trägt die Quelle nach.

---

## 6. Wartung

| Änderung | Wo |
|---|---|
| Neue Kategorie im Katalog | Migration + `ELSTER_CATEGORIES.csv` + Tabelle in `ELSTER_MAPPING.md` |
| Neuer Vordruck (Jahreswechsel) | Abschnitt 1.4 (Jahresvergleich) und alle Zeilennummern prüfen |
| Anderer Kontenrahmen beim Mandanten | nur `datev_skr03`/`datev_skr04` überschreiben, nicht die Doku |
| Neue Pflichtfelder (netto/USt/Vorsteuer) | Abschnitt 5 der Doku ist die Vorlage für die Migration |

---

## Кратко по-русски

Три файла: полная методичка (`ELSTER_MAPPING.md`), машиночитаемая таблица на 112 категорий
(`ELSTER_CATEGORIES.csv`) и эта инструкция. В CSV: `euer_line` — строка Anlage EÜR 2025,
`GRUPPE` — корневая категория без проводки, `n/a` — не расход. Все номера строк и Kz взяты из
официального формуляра BMF от 29.08.2025; сомнительные места помечены `prüfen`. Перед сдачей —
семь проверок из раздела 3; главное: личные изъятия не являются расходом, представительские —
70 %, подарки — до 50 €, путевые листы/1 % — только для служебного авто.
