# Anhang 1 — DATEV-Exportformat (VORLÄUFIG)

**Status: nicht mit einer Kanzlei abgestimmt.** Dieses Dokument hält fest, welches Arbeitsformat der Etappe 3
erzeugt. Es ist **kein** freigegebenes DATEV-Importformat. Der verbindliche Feldkatalog, die Spaltenreihenfolge,
die Kodierung und die DATEV-Kopfdaten werden mit der ersten Kanzlei abgestimmt und ersetzt dann dieses
Dokument (ТЗ §5.6, §12.1).

| | |
|---|---|
| Stand | 22.10.2026 |
| Grundlage | `docs/business/tz-kanzlei-edition.md`, §5.6 und §12.1 |
| Umsetzung | `DatevCsvWriter`, `DatevExportService` (Etappe 3) |
| Verbindlich ab | Unterschrift / Abnahmeprotokoll der ersten Kanzlei |

## 1. Was die Datei ist

Ein Paket der Belege eines Kontos, die in der Prüfliste auf `ZU_PRUEFEN` stehen und deren Belegdatum im
gewählten Zeitraum liegt. Der Export ist bewusst **kein** DATEV-Buchungsstapel im endgültigen Sinne: Er ist
lesbar, reproduzierbar und prüfbar, damit die Kanzlei ihn vor dem Import ansehen kann.

Jede Datei beginnt mit der Zeile

```
VORLÄUFIG / nicht mit der Kanzlei abgestimmt
```

Damit kann eine Datei, die das Haus verlässt, nicht für den abgestimmten Import gehalten werden.

## 2. Kodierung und Aufbau

| Merkmal | Arbeitsformat | Anmerkung |
|---|---|---|
| Zeichensatz | UTF-8 **mit BOM** | Excel öffnet die Datei ohne manuellen Importdialog. |
| Trennzeichen | `;` | Deutsche Excel-/DATEV-Erwartung. |
| Zeilenende | CRLF | Wie im Zielformat üblich. |
| Kopfzeile 1 | Warnhinweis (eine Zeile) | Nicht Teil der Spalten; der Hinweis belegt keine Spalte. |
| Kopfzeile 2 | Spaltenüberschriften | Genau neun Spalten. |
| Dezimaltrennzeichen | Komma, kein Tausendertrennzeichen (`1234,56`) | Ein fehlender Betrag bleibt leer, wird nicht `0,00`. |
| Datum | `TT.MM.JJJJ` | Z. B. `15.01.2026`. |
| Quoting | RFC 4180 | Felder mit `;`, `"` oder Zeilenumbruch werden gequotet, `"` verdoppelt. |

## 3. Spalten (vorläufig)

| # | Spalte | Inhalt | Quelle |
|---|---|---|---|
| 1 | `Belegnummer` | Beleg-/Rechnungsnummer | `documents.document_number`, sonst extrahierte `Rechnungsnummer`/`Aktenzeichen` |
| 2 | `Belegdatum` | Belegdatum | `documents.document_date` |
| 3 | `Mandant` | Name des Mandanten | `contacts.display_name` |
| 4 | `Lieferant` | Lieferant/Absender | `documents.sender`, sonst extrahierter `Lieferant` |
| 5 | `Betrag` | Bruttobetrag | extrahierter `Brutto`/`Betrag`/`OffenerBetrag` (normalisiert) |
| 6 | `Währung` | Währung | extrahierte `Waehrung`, sonst `EUR` |
| 7 | `Sollkonto` | Sachkonto Soll | `konto_mapping.sollkonto` |
| 8 | `Habenkonto` | Gegenkonto Haben | `konto_mapping.habenkonto` |
| 9 | `Steuerschlüssel` | DATEV-Steuerschlüssel | `konto_mapping.steuerschluessel` (darf leer sein) |

Nicht abgestimmt und deshalb **nicht** enthalten: DATEV-Kopfdaten (EXTF, Mandantennummer, Sachkontenlänge,
Wirtschaftsjahrsbeginn), Buchungstext, Belegfeld 2, Kostenstellen, DMS-Verknüpfung. Diese Punkte gehören in
die Abstimmung mit der Kanzlei und danach in eine neue Fassung dieses Anhangs.

## 4. Welche Belege in die Datei kommen

1. Das Konto ist das Konto der angemeldeten Sitzung.
2. Der Beleg steht auf `ZU_PRUEFEN` und ist nicht gelöscht.
3. Das Belegdatum liegt zwischen `periodFrom` und `periodTo` (beide einschließlich).
4. Ein optionaler Mandant filtert auf genau diesen Mandanten; er muss zum Konto gehören.
5. Der Beleg hat einen Mandanten. Ohne Mandanten wird er **nicht** exportiert, sondern als `SKIPPED` mit
   dem Grund `Ohne Mandant` in `export_batch_items` festgehalten.
6. Für Mandant/Lieferant/Kategorie existiert ein verwendbares `konto_mapping` mit Soll- und Habenkonto.
   Sonst wird der Beleg als `SKIPPED` mit `Kein Konto-Mapping` festgehalten.
7. Erfolgreich exportierte Belege wechseln über `DocumentService.changeBelegStatus` nach `EXPORTIERT` und
   werden auditiert. Ein erneuter Export desselben Zeitraums nimmt sie nicht wieder auf.

## 5. Konto-Mapping (vorläufig)

`konto_mapping` ordnet Mandant/Lieferant/Kategorie den Konten zu. Die spezifischste Zeile gewinnt:

`Mandant + Lieferant + Kategorie` → `Mandant + Lieferant` → `Mandant + Kategorie` → `Mandant` →
`Lieferant + Kategorie` → `Lieferant` → `Kategorie` → konto-weiter Standard.

Eine Zeile ohne Soll- und Habenkonto ist keine verwendbare Zuordnung und wird ignoriert. Der Steuerschlüssel
darf leer bleiben; die endgültige Schlüsselliste ist Teil der Abstimmung.

## 6. Was sich mit der ersten Kanzlei ändern wird

- exakte Spaltenreihenfolge und Pflichtfelder,
- DATEV-Kopfdaten und Buchungsstapel-Metadaten,
- Länge und Format von Sachkonten und Steuerschlüsseln,
- Kodierung (BOM ja/nein), Zeilenende, Dezimal- und Datumsformat,
- Umgang mit fehlenden Beträgen und Fremdwährungen,
- ob und wie Belegbilder/Belegfeld 2 mitgegeben werden.

Bis zur Abnahme gilt: **Die Datei ist ein Arbeitsformat, kein freigegebener DATEV-Import.**
