# ELSTER-/DATEV-Mapping: Ausgabenkatalog → Anlage EÜR

**Zweck.** Diese Datei bildet jeden der 112 Kategorien aus
`services/api/src/main/resources/db/migration/V81__expense_categories.sql` auf die amtliche
Anlage EÜR (Zeile + Kennzahl), auf die DATEV-Konten SKR03/SKR04 und auf die
Umsatzsteuer-Voranmeldung ab. Ein Buchhalter soll ohne Rückfrage sehen, **wo genau** ein Beleg
landet — und wo er **nicht** landen darf.

**Geltungsbereich.** Nur der Ausgabenkatalog (112 Kategorien, 8 Wurzelknoten + 104
Unterkategorien). Der Katalog enthält keine Erlöse; die Zusammenfassende Meldung (ZM) ist deshalb
hier nicht einschlägig (siehe Abschnitt 3.8).

**Rechtsstand.** Vordruck **Anlage EÜR 2025** (BMF-Schreiben vom 29.08.2025) sowie Vordruck
**USt 1 A 2025**. Die Zeilennummern der Anlage EÜR haben sich zwischen 2023 und 2024 verschoben
(2023: Waren = Zeile 26; 2024/2025: Waren = Zeile 27) — siehe Abschnitt 1.4. Alle Zeilen- und
Kz-Angaben in diesem Dokument beziehen sich auf den **Vordruck 2025**.

**Keine Steuerberatung.** Die Zuordnung Kategorie → Zeile ist eine fachliche Voreinstellung. Sie
ersetzt weder die Prüfung des Einzelfalls noch die Kontenrahmen-Anpassung des Mandanten.

---

## 0. Quellen und Sicherheitsgrade

Jede Zeilen- und Kz-Angabe ist einer der folgenden Quellen zugeordnet. Wo eine Quelle fehlt, steht
ausdrücklich **`prüfen (Quelle fehlt)`** — es werden keine Nummern geraten.

| Kürzel | Quelle | Verwendung |
|---|---|---|
| **[Q1]** | BMF, Schreiben v. 29.08.2025, GZ IV C 6 – S 2142/00023/010/001, „Standardisierte Einnahmenüberschussrechnung nach § 60 Abs. 4 EStDV; Anlage EÜR 2025" — Volltext mit Vordrucken `2025AnlEÜR801`–`804` und Anleitung. [PDF](https://www.steuerschroeder.de/blog/wp-content/uploads/2025/09/2025-08-29-anlage-EUER-2025.pdf) (Mirror des amtlichen Schreibens; Original auf bundesfinanzministerium.de) | **alle** Zeilennummern und Kz der Anlage EÜR, AfA-/GWG-/Bewirtungs-/Geschenke-Regeln der Anleitung |
| **[Q2]** | Vordruck **USt 1 A 2025**, Formularcode `2025USt1A561` (BMF-Muster der Vordrucke im USt-VA-Verfahren 2025), S. 402–404 des Formularpakets. [PDF](https://www.buhl.de/steuer/wp-content/uploads/sites/31/2025/05/alle-formulare-2024.pdf) — der Dateiname des Pakets sagt „2024", der enthaltene Vordruck ist der **2025er** (Code `2025USt1A561`) | alle Kz der Umsatzsteuer-Voranmeldung |
| **[Q3]** | § 4 EStG. [gesetze-im-internet.de](https://www.gesetze-im-internet.de/estg/__4.html) | Geschenke 50 €, Bewirtung 70 %, Gewerbesteuer-Abzugsfähigkeit |
| **[Q4]** | § 6 EStG. [gesetze-im-internet.de](https://www.gesetze-im-internet.de/estg/__6.html) | GWG 800 €, Sammelposten 250–1.000 €, Kfz-Bewertung |
| **[Q5]** | § 19 UStG. [gesetze-im-internet.de](https://www.gesetze-im-internet.de/ustg_1980/__19.html) | Kleinunternehmer 25.000 € / 100.000 € |
| **[Q6]** | § 13b UStG. [gesetze-im-internet.de](https://www.gesetze-im-internet.de/ustg_1980/__13b.html) | Reverse Charge |
| **[Q7]** | § 15 UStG. [gesetze-im-internet.de](https://www.gesetze-im-internet.de/ustg_1980/__15.html) | Vorsteuerabzug |
| **[Q8]** | DATEV Kontenrahmen SKR03 (Shop/Produktseite). [datev.de](https://www.datev.de/web/de/datev-shop/material/kontenrahmen-datev-skr-03/) | Referenz für SKR03 |
| **[Q9]** | DATEV Kontenrahmen SKR04 (Shop/Produktseite). [datev.de](https://www.datev.de/web/de/datev-shop/material/kontenrahmen-datev-skr-04/) | Referenz für SKR04 |
| **[Q10]** | Öffentliche SKR03-Kontenübersicht (EasyFirma, Kein DATEV-Original). [PDF](https://easyfirma.net/wp-content/uploads/2025/02/Kontenrahmen-SKR03-Handwerker.pdf) | Stichprobenprüfung einzelner Konten |
| **[Q11]** | ELSTER, „Alle Formulare" → EÜR. [elster.de](https://www.elster.de/eportal/formulare-leistungen/alleformulare/euer) | Navigation in Mein ELSTER |
| **[Q12]** | Vordruck Anlage EÜR **2023** (nur für den Jahresvergleich). [PDF](https://www.steuerschroeder.de/blog/wp-content/uploads/2023/09/2023-08-31-standardisierte-einnahmenueberschussrechnung-nach-paragraf-60-absatz-4-EStDV-anlage-EUER-2023.pdf) | Zeilenverschiebung 2023 → 2024/2025 |

**Sicherheitsgrade in den Tabellen**

- **Zeile/Kz ohne Zusatz** = aus [Q1] bzw. [Q2] wörtlich übernommen.
- **`prüfen`** = fachliche Zuordnung oder Kontonummer, für die in diesem Lauf keine belastbare
  Quelle vorlag.
- **`prüfen (Quelle fehlt)`** = es gibt für den konkreten Fall **keine** amtliche Zeile; die
  Zuordnung ist eine begründete Entscheidung, kein Zitat.

---

## 1. Aufbau der Anlage EÜR (Vordruck 2025)

Die Anlage EÜR ist in fünf Abschnitte gegliedert; die Zeilennummern sind fortlaufend und stehen in
der linken Spalte des Vordrucks, die **Kennzahl (Kz)** jeweils direkt am Feld. Die Kz sind der
Schlüssel für den amtlichen Datensatz (ELSTER) — sie werden bei der Übermittlung maschinell
gelesen [Q1, S. 3–5].

| Abschnitt | Zeilen | Inhalt |
|---|---|---|
| Allgemeine Angaben | 1–11 | Name, Steuernummer, Wirtschafts-Identifikationsnummer (Kz 101), Art des Betriebs (Kz 100), Rechtsform, Einkunftsart (Kz 103), Betriebsinhaber (Kz 104), Betriebsende (Kz 111), Grundstücksentnahme (Kz 120) |
| **1. Betriebseinnahmen** (einschl. steuerfreier) | 12–23 | Kleinunternehmer (Kz 111), nicht steuerbare Umsätze (Kz 119), Land-/Forstwirtschaft (Kz 104), umsatzsteuerpflichtige Einnahmen (Kz 112), steuerfreie / 0 % / § 13b (Kz 103), vereinnahmte USt (Kz 140), USt-Erstattung (Kz 141), Veräußerung/Entnahme Anlagevermögen (Kz 102), private Kfz-Nutzung (Kz 106), Sachentnahmen (Kz 108), Rücklagenauflösung, **Summe (Kz 159)** |
| **2. Betriebsausgaben** (einschl. auf steuerfreie Einnahmen entfallende) | 24–75 | Pauschalen 24–26 · Waren/Roh-/Hilfsstoffe **27** (Kz 100) · Übertrag 28 · Fremdleistungen **29** (Kz 110) · Personal **30** (Kz 120) · AfA **31–38** · Raumkosten **39–42** · sonstige unbeschränkt abziehbare **43–60** · Übertrag 61 · beschränkt abziehbare **62–67** · Kfz **68–74** · **Summe (Kz 199)** |
| **3. Ermittlung des Gewinns** | 76–98 | Summen, steuerfreie Einnahmen, Investitionsabzugsbeträge, § 4 Abs. 4a EStG, steuerpflichtiger Gewinn/Verlust (Kz 219) |
| **4. Ergänzende Angaben** | 99–105 | Rücklagen nach § 6c/§ 6b EStG, Ausgleichsposten § 4g EStG |
| **5. Zusätzliche Angaben bei Einzelunternehmen** | 106–107 | **Entnahmen** (Kz 122) / **Einlagen** (Kz 123) |

### 1.1 Zeilen der Anlage EÜR, die in diesem Mapping vorkommen

Alle folgenden Nummern sind aus [Q1, S. 3–5] übernommen; die Bezeichnung ist der amtliche
Wortlaut (gekürzt). Enthalten sind auch **Kontextzeilen**, die von keiner Kategorie direkt belegt
werden (z. B. 55/56 Schuldzinsen, 71–74 Kfz-Sonderfälle), weil die Sonderblöcke in Abschnitt 3
darauf verweisen.

| Zeile | Kz | Amtliche Bezeichnung (gekürzt) |
|---|---|---|
| 20 | 106 | Private Kfz-Nutzung — **Betriebseinnahme**, keine Ausgabe |
| 27 | 100 | Waren, Rohstoffe und Hilfsstoffe einschließlich der Nebenkosten |
| 29 | 110 | Bezogene Fremdleistungen |
| 30 | 120 | Ausgaben für eigenes Personal (z. B. Gehälter, Löhne und Versicherungsbeiträge) |
| 31 | 136 | AfA auf Grundstücke/grundstücksgleiche Rechte |
| 32 | 131 | AfA auf immaterielle Wirtschaftsgüter |
| 33 | 130 | AfA auf bewegliche Wirtschaftsgüter |
| 34 | 134 | Sonderabschreibungen nach § 7b EStG und § 7g Abs. 5 und 6 EStG |
| 35 | 138 | Herabsetzungsbeträge nach § 7g Abs. 2 Satz 3 EStG |
| 36 | 132 | Aufwendungen für geringwertige Wirtschaftsgüter nach § 6 Abs. 2 EStG |
| 37 | 137 | Auflösung Sammelposten nach § 6 Abs. 2a EStG |
| 38 | 135 | Restbuchwerte der ausgeschiedenen Anlagegüter |
| 39 | 150 | Miete/Pacht für Geschäftsräume und betrieblich genutzte Grundstücke |
| 40 | 152 | Aufwendungen für doppelte Haushaltsführung (z. B. Miete) |
| 41 | 151 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (ohne Schuldzinsen und AfA) |
| 42 | 153 | in Zeile 41 enthaltene Erhaltungsaufwendungen (Memo-Zeile) |
| 43 | 280 | Aufwendungen für Telekommunikation (z. B. Telefon, Internet) |
| 44 | 221 | Übernachtungs- und Reisenebenkosten bei Geschäftsreisen des Steuerpflichtigen |
| 45 | 281 | Fortbildungskosten (ohne Reisekosten) |
| 46 | 194 | Kosten für Rechts- und Steuerberatung, Buchführung |
| 47 | 222 | Miete/Leasing für bewegliche Wirtschaftsgüter (ohne Kfz) |
| 48 | 225 | Erhaltungsaufwendungen (Instandhaltung, Wartung, Reparatur; ohne Gebäude und Kfz) |
| 49 | 223 | Beiträge, Gebühren, Abgaben und Versicherungen (ohne Gebäude und Kfz) |
| 50 | 228 | Laufende EDV-Kosten (z. B. Beratung, Wartung, Reparatur) |
| 51 | 229 | Arbeitsmittel (z. B. Bürobedarf, Porto, Fachliteratur) |
| 52 | 226 | Kosten für Abfallbeseitigung und Entsorgung |
| 53 | 227 | Kosten für Verpackung und Transport |
| 54 | 224 | Werbekosten (z. B. Inserate, Werbespots, Plakate) |
| 55 | 232 | Schuldzinsen zur Finanzierung von Anschaffungs-/Herstellungskosten des Anlagevermögens |
| 56 | 234 | Übrige Schuldzinsen |
| 57 | 185 | Gezahlte und nach § 15 UStG abziehbare Vorsteuerbeträge |
| 58 | 186 | An das Finanzamt gezahlte und ggf. verrechnete Umsatzsteuer |
| 60 | 183 | Übrige unbeschränkt abziehbare Betriebsausgaben |
| 62 | 164 / 174 | Geschenke — **nicht abziehbar** (Kz 164) / **abziehbar** (Kz 174) |
| 63 | 165 / 175 | Bewirtungsaufwendungen — **nicht abziehbar** (Kz 165) / **abziehbar** (Kz 175) |
| 64 | 171 | Verpflegungsmehraufwendungen |
| 65 | 162 / 172 | Aufwendungen für ein häusliches Arbeitszimmer (nicht abziehbar / abziehbar) |
| 66 | 163 | Tagespauschale für die Tätigkeit in der häuslichen Wohnung |
| 67 | 168 / 177 | Sonstige beschränkt abziehbare Betriebsausgaben (nicht abziehbar / abziehbar) |
| 68 | 144 | Leasingkosten (Kfz) |
| 69 | 145 | Steuern, Versicherungen und Maut (Kfz) |
| 70 | 146 | Sonstige tatsächliche Fahrtkosten ohne AfA und Zinsen (Reparaturen, Wartungen, Treibstoff, Flugstrecken, öffentliche Verkehrsmittel) |
| 71 | 147 | Fahrtkosten für nicht zum Betriebsvermögen gehörende Fahrzeuge (Nutzungseinlage) |
| 72 | 142 − | Fahrtkosten Wege Wohnung ↔ erste Betriebsstätte; Familienheimfahrten |
| 73 | 176 + | Mindestens abziehbare Fahrtkosten (Entfernungspauschale); Familienheimfahrten |
| 74 | 139 − | Nicht abziehbare Beträge |
| 75 | 199 | Summe Betriebsausgaben |
| 106 | 122 | Entnahmen einschließlich Sach-, Leistungs- und Nutzungsentnahmen (**keine Ausgabe**) |

### 1.2 Zwei Spalten bei „beschränkt abziehbaren" Aufwendungen

Die Zeilen 62 und 63 (Geschenke, Bewirtung) haben **zwei Betragsspalten** — „nicht abziehbar" und
„abziehbar" [Q1, S. 5]. Der nicht abziehbare Teil wird in der EÜR also **nicht** einfach
weggelassen, sondern in der linken Spalte erklärt. Praxis: den Gesamtbetrag aufteilen, z. B.
Bewirtung 1.000 € netto → 700 € (Kz 175) + 300 € (Kz 165).

### 1.3 Netto oder brutto?

Die Betriebsausgaben sind grundsätzlich **netto** anzusetzen; die abziehbare Vorsteuer gehört in
Zeile 57 [Q1, Anleitung zu Zeile 57]. Kleinunternehmer (§ 19 UStG) und Unternehmer, die den
Vorsteuerabzug pauschalieren (§§ 23, 23a, 24 UStG), tragen den **Bruttobetrag** ein. Ist der
Vorsteuerabzug nach § 15 UStG ausgeschlossen (z. B. Geschenke über 50 €), gilt ebenfalls **brutto**
[Q1, Anleitung zu Zeile 57].

> **Konsequenz für das CRM:** Ohne ein gesetztes Mandanten-Merkmal „Kleinunternehmer /
> Regelbesteuerung" kann die App nicht entscheiden, ob netto oder brutto zu exportieren ist. Dieses
> Merkmal fehlt derzeit (siehe Abschnitt 5 und 6).

### 1.4 Jahresvergleich der Zeilennummern (2023 → 2024/2025)

Der Vordruck 2023 hatte eine andere Nummerierung; 2024 kamen u. a. die W-IdNr (Zeile 5) und die
Tagespauschale (Zeile 66) hinzu, wodurch sich alle folgenden Zeilen um +1 bis +2 verschoben haben
[Q12, S. 3–5 vs. Q1, S. 3–5]. Wer eine alte Arbeitsanweisung verwendet, verrutscht um eine Zeile:

| Inhalt | 2023 [Q12] | 2024/2025 [Q1] |
|---|---|---|
| Waren, Rohstoffe, Hilfsstoffe | 26 | **27** |
| Bezogene Fremdleistungen | 27 | **29** |
| Personal | 28 | **30** |
| AfA immobil / immateriell / beweglich | 30 / 31 / 32 | **31 / 32 / 33** |
| GWG | 35 | **36** |
| Sammelposten | 36 | **37** |
| Miete/Pacht Räume | 38 | **39** |
| Grundstücksaufwendungen | 40 | **41** |
| Telekommunikation | 41 | **43** |
| Übernachtung/Reisenebenkosten | 42 | **44** |
| Rechts-/Steuerberatung | 44 | **46** |
| Miete/Leasing beweglich | 45 | **47** |
| Erhaltungsaufwendungen | 46 | **48** |
| Beiträge/Gebühren/Versicherungen | 47 | **49** |
| EDV-Kosten | 48 | **50** |
| Arbeitsmittel | 49 | **51** |
| Abfall / Verpackung / Werbung | 50 / 51 / 52 | **52 / 53 / 54** |
| Vorsteuer / USt-Zahlung | 55 / 56 | **57 / 58** |
| Übrige Betriebsausgaben | 58 | **60** |
| Geschenke / Bewirtung / Verpflegung | 60 / 61 / 62 | **62 / 63 / 64** |
| Kfz: Leasing / Steuern / Fahrtkosten | 65 / 66 / 67 | **68 / 69 / 70** |
| Fahrtkosten Wohnung / Entfernungspauschale / nicht abziehbar | 68 / 69 / 70 | **71 / 72 / 73** |
| Summe Betriebsausgaben | 72 | **75** |

**Regel für die Praxis:** In ELSTER zählt die **Zeilenbezeichnung**, nicht die Nummer aus einer
alten Tabelle. Bei Abgabe für ein anderes Veranlagungsjahr die Nummer am aktuellen Vordruck
gegenprüfen.

---

## 2. Mapping-Katalog: alle 112 Kategorien

**Lesart der Tabellen**

- **EÜR-Zeile** = Zielzeile im Vordruck 2025 [Q1, S. 3–5]. `GRUPPE` = Wurzelknoten ohne eigene
  Buchung (nur Struktur). `n/a …` = bewusst **keine** Betriebsausgabe.
- **Kz / Bezeichnung** = amtlicher Feldname samt Kennzahl.
- **SKR03 / SKR04** = Konten aus dem Katalog (V81). Es sind **Startwerte**; der Kontenrahmen des
  Mandanten kann abweichen (siehe Abschnitt 6.1).
- **USt-VA-Kz** = Kennzahl der Umsatzsteuer-Voranmeldung, die der Beleg berührt [Q2, S. 402–404].
  `—` = keine USt-Berührung (steuerfreier Eingang, Pauschale, Lohn, Privat).
  `66*` = Vorsteuer nur, wenn die Rechnung USt ausweist bzw. die Voraussetzungen erfüllt sind
  (Begründung steht im Hinweis).
- **Hinweis** = Fallstricke, Abgrenzung, Prüfauftrag.

### Anlagevermögen (Investitionen) (`anlagevermoegen`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `anlagevermoegen` | Anlagevermögen (Investitionen) | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | — | Sammelkategorie: Investition. Kein direkter Ausgabenposten, Aufwand erst über AfA (Zeilen 31-38) bzw. AVEÜR. |
| `anlagevermoegen.maschinen` | Maschinen und Anlagen | 33 | AfA auf bewegliche Wirtschaftsgüter (Kz 130) | 0210 | 0400 | 66 | Kauf = Aktivierung im AVEÜR, Aufwand über AfA Zeile 33 (Nutzungsdauer). GWG bis 800 € netto -> Zeile 36. |
| `anlagevermoegen.fahrzeuge` | Fahrzeuge (Investition) | 33 | AfA auf bewegliche Wirtschaftsgüter (Kz 130) | 0320 | 0520 | 66 | Aktivierung AVEÜR, AfA Zeile 33 (6 Jahre bei Pkw, § 7 Abs. 1 EStG / AfA-Tabelle). Private Nutzung -> Zeile 20. |
| `anlagevermoegen.betriebsausstattung` | Betriebs- und Geschäftsausstattung | 33 | AfA auf bewegliche Wirtschaftsgüter (Kz 130) | 0410 | 0500 | 66 | Aktivierung AVEÜR, AfA Zeile 33. Bis 800 € netto GWG Zeile 36, 250-1.000 € Sammelposten Zeile 37. |
| `anlagevermoegen.bueromoebel` | Büromöbel | 33 | AfA auf bewegliche Wirtschaftsgüter (Kz 130) | 0420 | 0650 | 66 | Aktivierung AVEÜR, AfA Zeile 33. Bis 800 € netto GWG Zeile 36 (Möbel meist 13 Jahre). |
| `anlagevermoegen.it_hardware` | IT-Hardware | 33 | AfA auf bewegliche Wirtschaftsgüter (Kz 130) | 0490 | 0690 | 66 | AfA Zeile 33; für Hardware/Software kann Nutzungsdauer 1 Jahr angenommen werden (BMF v. 22.02.2022) -> volle AfA im Jahr. GWG Zeile 36 möglich. |
| `anlagevermoegen.software_lizenzen` | Software und Lizenzen | 32 | AfA auf immaterielle Wirtschaftsgüter (Kz 131) | 0027 | 0135 | 66 | Dauerhafte Lizenz = immaterielles WG, AfA Zeile 32. Abo/SaaS dagegen Zeile 50 (keine Aktivierung). |
| `anlagevermoegen.gebaeude` | Gebäude | 31 | AfA auf Grundstücke/grundstücksgleiche Rechte (Kz 136) | 0080 | 0230 | 66 | AfA auf Gebäude Zeile 31 (AVEÜR Zeile 22). Gebäudeanteil und Grund und Boden trennen; Grund und Boden nicht abschreibbar. |
| `anlagevermoegen.gwg` | Geringwertige Wirtschaftsgüter (GWG) | 36 | GWG nach § 6 Abs. 2 EStG (Kz 132) | 4855 | 6260 | 66 | Sofortabzug § 6 Abs. 2 EStG bis 800 € netto (Kz 132). Kein AVEÜR-Eintrag. Verzeichnis führen (>250 €). |
| `anlagevermoegen.gwg_sammelposten` | GWG-Sammelposten | 37 | Auflösung Sammelposten § 6 Abs. 2a EStG (Kz 137) | 4862 | 6264 | 66 | Sammelposten § 6 Abs. 2a EStG: 250-1.000 € netto, Auflösung 1/5 pro Jahr (Kz 137). Wahlrecht nur einheitlich je Wj. |

### Umlaufvermögen / Material (`material`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `material` | Umlaufvermögen / Material | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | 66 | Sammelkategorie: Wareneingang/Material, siehe Unterkategorien (Zeile 27). |
| `material.waren` | Waren und Handelsware | 27 | Waren, Rohstoffe und Hilfsstoffe einschl. Nebenkosten (Kz 100) | 3400 | 5400 | 66 | Wareneingang netto (Kz 100). EU-Erwerb: USt-VA Kz 89 + Vorsteuer Kz 61 statt Kz 66. |
| `material.rohstoffe` | Rohstoffe | 27 | Waren, Rohstoffe und Hilfsstoffe einschl. Nebenkosten (Kz 100) | 4000 | 5000 | 66 | Rohstoffe netto (Kz 100). Bei Vorsteuerabzug netto, sonst brutto (Kleinunternehmer). |
| `material.hilfsstoffe` | Hilfsstoffe | 27 | Waren, Rohstoffe und Hilfsstoffe einschl. Nebenkosten (Kz 100) | 4000 | 5000 | 66 | Hilfsstoffe netto (Kz 100). Abgrenzung zu Werkzeugverbrauchsmaterial beachten (Zeile 51). |
| `material.betriebsstoffe` | Betriebsstoffe | 27 | Waren, Rohstoffe und Hilfsstoffe einschl. Nebenkosten (Kz 100) | 4000 | 5000 | 66 | Betriebsstoffe netto (Kz 100). Kein Bestandteil des Erzeugnisses, aber Verbrauch im Betrieb. |
| `material.verpackung` | Verpackungsmaterial | 53 | Verpackung und Transport (Kz 227) | 4710 | 6710 | 66 | Verpackungsmaterial für Warenabgabe (Kz 227). Verpackung, die in das Produkt eingeht, ggf. Zeile 27. |
| `material.fremdleistungen` | Fremdleistungen und Fremdarbeiten | 29 | Bezogene Fremdleistungen (Kz 110) | 4909 | 6303 | 66 | Subunternehmer/Bezogene Fremdleistungen netto (Kz 110). Bei Bauleistungen § 13b UStG: USt-VA Kz 84/85 + Vorsteuer Kz 67. |

### Werkzeuge (`werkzeuge`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `werkzeuge` | Werkzeuge | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | 66 | Sammelkategorie: Werkzeuge, siehe Unterkategorien (Zeile 51 bzw. 36/33). |
| `werkzeuge.handwerkzeug` | Handwerkzeug | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4985 | 6845 | 66 | Arbeitsmittel (Kz 229). Bis 800 € netto als GWG Zeile 36 aktivieren/sofort abschreiben; darüber Zeile 33. |
| `werkzeuge.elektrowerkzeuge` | Elektrowerkzeuge | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4985 | 6845 | 66 | Arbeitsmittel (Kz 229); ab 800 € netto GWG Zeile 36 oder AfA Zeile 33. |
| `werkzeuge.maschinen_werkzeuge` | Werkzeugmaschinen | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4985 | 6845 | 66 | Arbeitsmittel (Kz 229); stationäre Maschinen ab 800 € netto aktivieren -> AfA Zeile 33. |
| `werkzeuge.messgeraete` | Messgeräte | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4985 | 6845 | 66 | Arbeitsmittel (Kz 229); Eich-/Kalibrierkosten ebenfalls hier. |
| `werkzeuge.verbrauchsmaterial_werkzeug` | Verbrauchsmaterial für Werkzeuge | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4980 | 6850 | 66 | Arbeitsmittel/Betriebsbedarf (Kz 229). Alternativ Zeile 27, wenn unmittelbar in die Leistung eingehend. |
| `werkzeuge.werkzeug_reparatur` | Werkzeugreparatur | 48 | Erhaltungsaufwendungen ohne Gebäude/Kfz (Kz 225) | 4805 | 6470 | 66 | Erhaltungsaufwendungen (Kz 225), nicht AfA. |
| `werkzeuge.werkzeug_leasing` | Werkzeugmiete und -leasing | 47 | Miete/Leasing für bewegliche WG ohne Kfz (Kz 222) | 4965 | 6840 | 66 | Miete/Leasing bewegliche WG ohne Kfz (Kz 222). Kein Kfz-Leasing (Zeile 68). |

### Fahrzeugkosten (`fahrzeug`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `fahrzeug` | Fahrzeugkosten | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | 66 | Sammelkategorie: Kfz. Laufende Kosten Zeilen 68-70, private Nutzung Zeile 20. |
| `fahrzeug.kraftstoff` | Kraftstoff | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4530 | 6530 | 66 | Sonstige tatsächliche Fahrtkosten, Treibstoff (Kz 146). Tankbelege nur bei betrieblichem Fahrzeug voll abziehbar. |
| `fahrzeug.strom_laden` | Ladestrom (E-Fahrzeug) | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4530 | 6530 | 66 | Ladestrom E-Fahrzeug (Kz 146). Wallbox im Betriebsvermögen: AfA Zeile 33, Strom Zeile 70. |
| `fahrzeug.reparatur` | Kfz-Reparaturen | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4540 | 6540 | 66 | Reparaturen Kfz (Kz 146); bei >50 % betrieblicher Nutzung voll, sonst anteilig. |
| `fahrzeug.wartung` | Wartung und Inspektion | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4540 | 6540 | 66 | Wartung/Inspektion (Kz 146). HU/AU-Gebühren ebenfalls hier. |
| `fahrzeug.versicherung` | Kfz-Versicherung | 69 | Kfz-Steuern, -Versicherungen und Maut (Kz 145) | 4520 | 6520 | — | Kfz-Versicherung (Kz 145). Keine Vorsteuer, da Versicherung umsatzsteuerfrei -> Spalte USt-Kz "—". |
| `fahrzeug.kfz_steuer` | Kfz-Steuer | 69 | Kfz-Steuern, -Versicherungen und Maut (Kz 145) | 4510 | 7685 | — | Kfz-Steuer (Kz 145), kein Vorsteuerabzug. |
| `fahrzeug.leasing` | Kfz-Leasing | 68 | Kfz-Leasingkosten (Kz 144) | 4570 | 6560 | 66 | Kfz-Leasing (Kz 144). Sonderzahlungen anteilig; 1-%-Regel/Fahrtenbuch für Privatanteil beachten. |
| `fahrzeug.miete` | Mietwagen und Carsharing | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4595 | 6595 | 66 | Mietwagen/Carsharing (Kz 146). Bei Reise: Parken/Übernachtung ggf. Zeile 44. |
| `fahrzeug.reifen` | Reifen und Räder | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4530 | 6530 | 66 | Reifen/Räder (Kz 146); Einlagerung ebenfalls. Reifensatz >800 € netto ggf. aktivieren. |
| `fahrzeug.pflege` | Fahrzeugpflege und Wäsche | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4530 | 6530 | 66 | Fahrzeugwäsche/Pflege (Kz 146). |
| `fahrzeug.parken` | Parken und Garagen | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4550 | 6550 | 66 | Parkgebühren (Kz 146). Auf Geschäftsreise auch als Reisenebenkosten Zeile 44 vertretbar. |
| `fahrzeug.maut` | Maut und Vignetten | 69 | Kfz-Steuern, -Versicherungen und Maut (Kz 145) | 4560 | 6580 | 66* | Maut/Vignetten (Kz 145). Lkw-Maut ist nicht vorsteuerpflichtig, wenn pauschal -> prüfen. |
| `fahrzeug.sonstiges` | Sonstige Fahrzeugkosten | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4580 | 6570 | 66 | Übrige laufende Kfz-Kosten (Kz 146). |
| `fahrzeug.privatnutzung` | Private Kfz-Nutzung | 20 | Private Kfz-Nutzung (Betriebseinnahme, Kz 106) | 4590 | 6590 | — | Korrekturposten als BETRIEBSEINNAHME (Kz 106), keine Ausgabe. 1-%-Regel oder Fahrtenbuch; USt auf unentgeltliche Wertabgabe in USt-VA. |

### Betriebsausgaben (`betriebsausgaben`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `betriebsausgaben` | Betriebsausgaben | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | 66 | Sammelkategorie: laufende Betriebsausgaben, siehe Unterkategorien (Zeilen 39-60). |
| `betriebsausgaben.miete` | Miete für Räume | 39 | Miete/Pacht für Geschäftsräume und Grundstücke (Kz 150) | 4210 | 6310 | 66* | Miete Räume (Kz 150). Umsatzsteuerfreie Vermietung -> kein Vorsteuerabzug. |
| `betriebsausgaben.pacht` | Pacht | 39 | Miete/Pacht für Geschäftsräume und Grundstücke (Kz 150) | 4220 | 6315 | 66* | Pacht Grundstücke/Anlagen (Kz 150). |
| `betriebsausgaben.nebenkosten_raum` | Mietnebenkosten | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4228 | 6318 | 66 | Nebenkosten betrieblich genutzter Grundstücke (Kz 151); nicht Zeile 39, wenn gesondert abgerechnet. |
| `betriebsausgaben.heizung` | Heizung | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4230 | 6320 | 66 | Heizkosten (Kz 151). Keine eigene EÜR-Zeile für Heizung. |
| `betriebsausgaben.strom` | Strom | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4240 | 6325 | 66 | Strom (Kz 151). Bei Produktionsstrom ggf. Zeile 27/48 prüfen. |
| `betriebsausgaben.gas` | Gas | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4240 | 6325 | 66 | Gas (Kz 151). |
| `betriebsausgaben.wasser` | Wasser und Abwasser | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4240 | 6325 | 66 | Wasser/Abwasser (Kz 151). |
| `betriebsausgaben.versicherungen` | Betriebsversicherungen | 49 | Beiträge, Gebühren, Abgaben und Versicherungen (Kz 223) | 4360 | 6400 | — | Betriebsversicherungen (Kz 223). Versicherung umsatzsteuerfrei -> kein Vorsteuerabzug. |
| `betriebsausgaben.versicherung_gebaeude` | Gebäudeversicherung | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4366 | 6405 | — | Gebäudeversicherung (Kz 151), da Zeile 49 Gebäudeversicherungen ausdrücklich ausnimmt. |
| `betriebsausgaben.buero` | Bürobedarf | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4930 | 6815 | 66 | Bürobedarf (Kz 229). |
| `betriebsausgaben.telefon` | Telefon | 43 | Aufwendungen für Telekommunikation (Kz 280) | 4920 | 6805 | 66 | Telekommunikation (Kz 280). Privatanteil kürzen. |
| `betriebsausgaben.internet` | Internet und Telefax | 43 | Aufwendungen für Telekommunikation (Kz 280) | 4925 | 6810 | 66 | Internet/Telefax (Kz 280). Privatanteil kürzen. |
| `betriebsausgaben.porto` | Porto und Versand | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4910 | 6800 | 66 | Porto (Kz 229, ausdrücklich genannt). |
| `betriebsausgaben.reisekosten` | Reisekosten | 44 | Übernachtungs- und Reisenebenkosten (Kz 221) | 4670 | 6670 | 66 | Reisenebenkosten (Kz 221). Fahrtkosten separat Zeile 70, Verpflegung Zeile 64. |
| `betriebsausgaben.uebernachtung` | Übernachtungskosten | 44 | Übernachtungs- und Reisenebenkosten (Kz 221) | 4676 | 6680 | 66 | Übernachtung Geschäftsreise (Kz 221). Hotel 7 % USt, Vorsteuer Kz 66. |
| `betriebsausgaben.verpflegung` | Verpflegungsmehraufwand | 64 | Verpflegungsmehraufwendungen (Kz 171) | 4674 | 6674 | — | Nur Pauschbeträge (Kz 171): 28 €/24 h, 14 €/8 h (Inland). Kein Vorsteuerabzug auf Pauschalen. |
| `betriebsausgaben.fahrtkosten` | Fahrtkosten | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4673 | 6673 | 66 | Bahn/Bus/Taxi (Kz 146). Entfernungspauschale separat Zeile 73. |
| `betriebsausgaben.flug` | Flugkosten | 70 | Sonstige tatsächliche Fahrtkosten (Kz 146) | 4673 | 6673 | 66 | Flugkosten (Kz 146, ausdrücklich "Kosten für Flugstrecken"). |
| `betriebsausgaben.fortbildung` | Fortbildung und Schulung | 45 | Fortbildungskosten (Kz 281) | 4945 | 6821 | 66 | Fortbildung (Kz 281), ohne Reisekosten. |
| `betriebsausgaben.marketing` | Werbung und Marketing | 54 | Werbekosten (Kz 224) | 4600 | 6600 | 66 | Werbekosten (Kz 224). |
| `betriebsausgaben.beratung` | Beratung und Honorare | 60 | Übrige unbeschränkt abziehbare Betriebsausgaben (Kz 183) | 4950 | 6825 | 66 | Übrige Betriebsausgaben (Kz 183). Rechts-/Steuerberatung stattdessen Zeile 46. |
| `betriebsausgaben.steuerberatung` | Steuerberatung und Buchführung | 46 | Rechts- und Steuerberatung, Buchführung (Kz 194) | 4955 | 6830 | 66 | Rechts- und Steuerberatung, Buchführung (Kz 194). |
| `betriebsausgaben.rechtsberatung` | Rechtsberatung | 46 | Rechts- und Steuerberatung, Buchführung (Kz 194) | 4950 | 6825 | 66 | Rechtsberatung (Kz 194); Prozesskosten mit betrieblichem Bezug. |
| `betriebsausgaben.software_it` | Software und Abonnements | 50 | Laufende EDV-Kosten (Kz 228) | 4964 | 6837 | 67 | Laufende EDV-Kosten (Kz 228). Ausland/SaaS: Reverse Charge -> USt-VA Kz 84/85 (EU: 46/47) + Vorsteuer Kz 67. |
| `betriebsausgaben.hosting_cloud` | Hosting und Cloud | 50 | Laufende EDV-Kosten (Kz 228) | 4964 | 6837 | 67 | EDV-Kosten (Kz 228). Ausländischer Anbieter: § 13b UStG, USt-VA Kz 84/85 (EU: 46/47), Vorsteuer Kz 67. |
| `betriebsausgaben.wartung_software` | Wartung von Hard- und Software | 50 | Laufende EDV-Kosten (Kz 228) | 4806 | 6495 | 66 | EDV-Kosten Wartung/Support (Kz 228). |
| `betriebsausgaben.beitraege` | Beiträge (Kammern, Verbände) | 49 | Beiträge, Gebühren, Abgaben und Versicherungen (Kz 223) | 4380 | 6420 | — | Kammer-/Verbandsbeiträge (Kz 223); meist ohne USt. |
| `betriebsausgaben.gebuehren` | Gebühren und Abgaben | 49 | Beiträge, Gebühren, Abgaben und Versicherungen (Kz 223) | 4390 | 6430 | 66* | Gebühren/Abgaben (Kz 223). Behördliche Gebühren oft ohne USt. |
| `betriebsausgaben.bankgebuehren` | Bankgebühren | 60 | Übrige unbeschränkt abziehbare Betriebsausgaben (Kz 183) | 4970 | 6855 | — | Übrige Betriebsausgaben (Kz 183). Bankentgelte meist umsatzsteuerfrei -> kein Vorsteuerabzug. |
| `betriebsausgaben.nebenkosten_geldverkehr` | Nebenkosten des Geldverkehrs | 60 | Übrige unbeschränkt abziehbare Betriebsausgaben (Kz 183) | 4970 | 6855 | — | Übrige Betriebsausgaben (Kz 183); Kontoführung/Dispozinsen. |
| `betriebsausgaben.abschreibungen` | Abschreibungen (AfA) | 31-38 | AfA-Sammelzeilen 31–38 (Kz 136/131/130/134/138/132/137/135) | 4830 | 6220 | 66 | Sammelzeilen der AfA: 31 Gebäude, 32 immateriell, 33 beweglich, 34 Sonder-AfA, 35 Herabsetzung, 36 GWG, 37 Sammelposten, 38 Restbuchwert. |
| `betriebsausgaben.bewirtung` | Bewirtungskosten | 63 | Bewirtungsaufwendungen (Kz 165 / 175) | 4650 | 6640 | 66 | 70 % abziehbar (Kz 175), 30 % nicht abziehbar (Kz 165). Vorsteuer VOLL abziehbar (Kz 66). Angaben Ort/Tag/Teilnehmer/Anlass. |
| `betriebsausgaben.geschenke` | Geschenke | 62 | Geschenke (Kz 164 nicht abziehbar / 174 abziehbar) | 4630 | 6610 | 66* | Bis 50 € netto je Empfänger/Jahr abziehbar (Kz 174); darüber nicht abziehbar (Kz 164) und kein Vorsteuerabzug. Empfänger dokumentieren. |
| `betriebsausgaben.arbeitskleidung` | Arbeitskleidung | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4980 | 6850 | 66 | Arbeitsmittel (Kz 229). Nur typische Berufskleidung; bürgerliche Kleidung nicht abziehbar. |
| `betriebsausgaben.arbeitssicherheit` | Arbeitssicherheit | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4980 | 6850 | 66 | Arbeitsmittel (Kz 229); Arbeitsschutzartikel. |
| `betriebsausgaben.entsorgung` | Entsorgung und Abfall | 52 | Abfallbeseitigung und Entsorgung (Kz 226) | 4969 | 6859 | 66 | Abfall/Entsorgung (Kz 226). |
| `betriebsausgaben.reinigung` | Reinigung | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4250 | 6330 | 66 | Gebäudereinigung (Kz 151). Reinigungsmittel für Räume ebenfalls hier. |
| `betriebsausgaben.reparatur_instandhaltung` | Reparatur und Instandhaltung | 48 | Erhaltungsaufwendungen ohne Gebäude/Kfz (Kz 225) | 4809 | 6490 | 66 | Erhaltungsaufwendungen (Kz 225); ohne Gebäude (Zeile 41/42) und Kfz (Zeile 70). |
| `betriebsausgaben.instandhaltung_gebaeude` | Instandhaltung von Gebäuden | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4801 | 6450 | 66 | Gebäudeinstandhaltung (Kz 151); Erhaltungsaufwand zusätzlich als Memo in Zeile 42 (Kz 153). |
| `betriebsausgaben.instandhaltung_maschinen` | Instandhaltung von Maschinen | 48 | Erhaltungsaufwendungen ohne Gebäude/Kfz (Kz 225) | 4800 | 6460 | 66 | Erhaltungsaufwand Maschinen (Kz 225). |
| `betriebsausgaben.instandhaltung_ausstattung` | Instandhaltung von Ausstattung | 48 | Erhaltungsaufwendungen ohne Gebäude/Kfz (Kz 225) | 4805 | 6470 | 66 | Erhaltungsaufwand BGA (Kz 225). |
| `betriebsausgaben.miete_bewegliche` | Miete beweglicher Wirtschaftsgüter | 47 | Miete/Leasing für bewegliche WG ohne Kfz (Kz 222) | 4960 | 6835 | 66 | Miete bewegliche WG (Kz 222); ohne Kfz. |
| `betriebsausgaben.leasing_bewegliche` | Leasing beweglicher Wirtschaftsgüter | 47 | Miete/Leasing für bewegliche WG ohne Kfz (Kz 222) | 4965 | 6840 | 66 | Leasing bewegliche WG (Kz 222); Kfz-Leasing Zeile 68. |
| `betriebsausgaben.literatur` | Fachliteratur und Zeitschriften | 51 | Arbeitsmittel: Bürobedarf, Porto, Fachliteratur (Kz 229) | 4940 | 6820 | 66 | Fachliteratur (Kz 229, ausdrücklich genannt). |

### Löhne / Gehälter (`loehne`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `loehne` | Löhne / Gehälter | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | — | Sammelkategorie: Personalaufwand, siehe Unterkategorien (Zeile 30). |
| `loehne.gehaelter` | Gehälter | 30 | Ausgaben für eigenes Personal (Kz 120) | 4120 | 6020 | — | Personalaufwand (Kz 120). Lohnsteuer/Soli/KiSt werden über Lohnsteuer-Anmeldung abgeführt, nicht hier. |
| `loehne.loehne` | Löhne | 30 | Ausgaben für eigenes Personal (Kz 120) | 4110 | 6010 | — | Personalaufwand (Kz 120). |
| `loehne.aushilfen` | Aushilfen | 30 | Ausgaben für eigenes Personal (Kz 120) | 4190 | 6030 | — | Personalaufwand (Kz 120); kurzfristige Beschäftigung. |
| `loehne.minijob` | Minijob | 30 | Ausgaben für eigenes Personal (Kz 120) | 4195 | 6035 | — | Personalaufwand (Kz 120); pauschale Abgaben separat (siehe minijob_abgaben). |
| `loehne.auszubildende` | Auszubildende | 30 | Ausgaben für eigenes Personal (Kz 120) | 4110 | 6010 | — | Personalaufwand (Kz 120); Ausbildungsvergütung. |
| `loehne.geschaeftsfuehrer` | Geschäftsführergehälter | 30 | Ausgaben für eigenes Personal (Kz 120) | 4127 | 6027 | — | Personalaufwand (Kz 120). Bei Gesellschafter-Geschäftsführer Angemessenheit prüfen (verdeckte Gewinnausschüttung). |
| `loehne.sozialversicherung` | Sozialversicherung | 30 | Ausgaben für eigenes Personal (Kz 120) | 4130 | 6110 | — | Arbeitgeberanteile (Kz 120, "Versicherungsbeiträge" im Zeilentext). |
| `loehne.berufsgenossenschaft` | Berufsgenossenschaft | 30 | Ausgaben für eigenes Personal (Kz 120) | 4138 | 6120 | — | BG-Beiträge (Kz 120) als Personalnebenkosten. Alternativ Zeile 49 vertretbar -> prüfen. |
| `loehne.minijob_abgaben` | Pauschalabgaben Minijob | 30 | Ausgaben für eigenes Personal (Kz 120) | 4144 | 6171 | — | Pauschalabgaben Minijob (Kz 120). |
| `loehne.vermoegenswirksame_leistungen` | Vermögenswirksame Leistungen | 30 | Ausgaben für eigenes Personal (Kz 120) | 4170 | 6080 | — | Vermögenswirksame Leistungen (Kz 120). |
| `loehne.freiwillige_sozialleistungen` | Freiwillige Sozialleistungen | 30 | Ausgaben für eigenes Personal (Kz 120) | 4140 | 6130 | — | Freiwillige Sozialleistungen (Kz 120). |

### Steuern / Abgaben (`steuern`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `steuern` | Steuern / Abgaben | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | — | Sammelkategorie: Steuern/Abgaben, Zielzeile je Steuerart (41/58/60/67). |
| `steuern.umsatzsteuer` | Umsatzsteuer | 58 | An das Finanzamt gezahlte Umsatzsteuer (Kz 186) | 1776 | 3806 | — | USt-Zahllast (Kz 186); Vorsteuer separat Zeile 57. USt ist keine Betriebsausgabe im engeren Sinn, wird aber als Ausgabe erfasst. |
| `steuern.gewerbesteuer` | Gewerbesteuer | 67 | Sonstige beschränkt abziehbare Betriebsausgaben (Kz 168 / 177) | 4320 | 7610 | — | Gewerbesteuer ist NICHT abziehbar (§ 4 Abs. 5b EStG) -> Spalte "nicht abziehbar" (Kz 168). |
| `steuern.grundsteuer` | Grundsteuer | 41 | Sonstige Aufwendungen für betrieblich genutzte Grundstücke (Kz 151) | 4270 | 6340 | — | Grundsteuer betrieblicher Grundstücke (Kz 151); kein Vorsteuerabzug. |
| `steuern.sonstige_betriebssteuern` | Sonstige Betriebssteuern | 67 | Sonstige beschränkt abziehbare Betriebsausgaben (Kz 168 / 177) | 4340 | 6430 | — | Je Steuerart: abziehbar -> Zeile 60, nicht abziehbar -> Zeile 67. Zuordnung fachlich prüfen. |
| `steuern.saeumniszuschlag` | Säumniszuschläge und Zwangsgeld | 60 | Übrige unbeschränkt abziehbare Betriebsausgaben (Kz 183) | 4397 | 6437 | — | Steuerliche Nebenleistungen (Verspätungs-/Säumniszuschlag) sind abziehbar -> Zeile 60 (Kz 183). |

### Privatentnahme (nicht betrieblich) (`privatentnahme`)

| Kategorie (code) | Name (de) | EÜR-Zeile | Kz / Bezeichnung | SKR03 | SKR04 | USt-VA-Kz | Hinweis |
|---|---|---|---|---|---|---|---|
| `privatentnahme` | Privatentnahme (nicht betrieblich) | GRUPPE | Sammelkategorie (keine eigene EÜR-Zeile) | — | — | — | Sammelkategorie: nicht betrieblich. Keine Betriebsausgabe, keine EÜR-Ausgabenzeile. |
| `privatentnahme.geld` | Geldentnahme | n/a (Entnahme 106) | Entnahmen Zeile 106 | 1800 | 2100 | — | Keine Betriebsausgabe. Geldentnahme = Entnahme (Zeile 106), nicht Zeile 24-75. |
| `privatentnahme.waren` | Warenentnahme | n/a (Entnahme 106) | Entnahmen Zeile 106 | 1800 | 2100 | — | Keine Betriebsausgabe. Warenentnahme: Entnahme Zeile 106, USt auf unentgeltliche Wertabgabe. |
| `privatentnahme.ausgaben` | Private Ausgaben | n/a (privat) | keine EÜR-Zeile (nicht betrieblich) | 1800 | 2100 | — | Keine Betriebsausgabe, keine EÜR-Zeile. Nicht buchen. |
| `privatentnahme.versicherung` | Private Versicherungen | n/a (privat) | keine EÜR-Zeile (nicht betrieblich) | 1800 | 2100 | — | Keine Betriebsausgabe (private Versicherung). |
| `privatentnahme.kfz` | Private Kfz-Kosten | n/a (privat) | keine EÜR-Zeile (nicht betrieblich) | 1800 | 2100 | — | Private Kfz-Kosten: keine Betriebsausgabe. Betriebliches Kfz siehe Zeile 20. |
| `privatentnahme.reisen` | Private Reisen | n/a (privat) | keine EÜR-Zeile (nicht betrieblich) | 1800 | 2100 | — | Keine Betriebsausgabe (Urlaubsreise). |
| `privatentnahme.steuern` | Einkommensteuer | n/a (privat) | keine EÜR-Zeile (nicht betrieblich) | 1800 | 2100 | — | Einkommensteuer ist Privatsache, keine Betriebsausgabe. |
| `privatentnahme.lebenshaltung` | Lebenshaltung | n/a (privat) | keine EÜR-Zeile (nicht betrieblich) | 1800 | 2100 | — | Keine Betriebsausgabe (Lebenshaltung). |

### 2.1 Verteilung (Plausibilitätskontrolle)

- 112 Kategorien, davon 8 Wurzelknoten (`GRUPPE`) und 8 bewusst nicht betriebliche
  Privatentnahmen (`n/a`).
- 96 Kategorien werden auf **33 verschiedene** EÜR-Zeilen abgebildet. Mehrfachbelegung ist
  normal und gewollt (z. B. 11 Kategorien → Zeile 70 „Sonstige tatsächliche Fahrtkosten").
- Keine Kategorie zeigt auf eine Zeile außerhalb des Vordrucks 2025.

---

## 3. Sonderblöcke

### 3.1 Umsatzsteuer (Umsatzsteuer-Voranmeldung)

Die USt-VA ist **kein** Bestandteil der EÜR, sondern eine eigene Anmeldung. Sie berührt unseren
Katalog an zwei Stellen: (a) als **Vorsteuer** aus Eingangsrechnungen, (b) als **USt-Zahllast**,
die in der EÜR als Ausgabe (Zeile 58) erscheint.

**Kennzahlen im Vordruck USt 1 A 2025** [Q2, S. 402–404]:

| Sachverhalt | Zeile | Kz |
|---|---|---|
| Steuerpflichtige Umsätze 19 % | 13 | **81** |
| Steuerpflichtige Umsätze 7 % | 14 | **86** |
| Steuerpflichtige Umsätze 0 % | 15 | **87** |
| Steuerfreie innergemeinschaftliche Lieferung mit USt-IdNr | 19 | 41 |
| Weitere steuerfreie Umsätze mit Vorsteuerabzug (z. B. Ausfuhr) | 22 | 43 |
| Steuerfreie Umsätze **ohne** Vorsteuerabzug | 23 | 48 |
| Innergemeinschaftlicher Erwerb 19 % | 25 | **89** |
| Innergemeinschaftlicher Erwerb 7 % | 26 | 93 |
| § 13b Abs. 1 UStG — sonstige Leistungen eines EU-Unternehmers | 30 | **46 / 47** |
| § 13b Abs. 2 Nr. 1, 2, 4–12 UStG — „Andere Leistungen" | 32 | **84 / 85** |
| Vorsteuer aus Rechnungen von anderen Unternehmern | 38 | **66** |
| Vorsteuer aus innergemeinschaftlichem Erwerb | 39 | 61 |
| Entstandene Einfuhrumsatzsteuer | 40 | 62 |
| Vorsteuer aus Leistungen im Sinne des § 13b UStG | 41 | **67** |
| Verbleibende USt-Vorauszahlung / Überschuss | 50 | **83** |

**Zuordnung zu unseren Kategorien**

| Fall im Katalog | USt-VA | Begründung |
|---|---|---|
| Inlandsrechnung mit 19 % oder 7 % USt (Material, Miete, Reisen, Werbung, EDV …) | **Kz 66** (Zeile 38) | Vorsteuer aus Rechnungen anderer Unternehmer, § 15 Abs. 1 Satz 1 Nr. 1 UStG [Q7] |
| Wareneinkauf aus dem EU-Ausland (`material.waren`) | **Kz 89** + **Kz 61** | Innergemeinschaftlicher Erwerb: Steuer und Vorsteuer in derselben Anmeldung |
| SaaS/Hosting/Beratung eines EU-Anbieters (`software_it`, `hosting_cloud`, `beratung`) | **Kz 46/47** + **Kz 67** | Sonstige Leistung eines EU-Unternehmers, § 13b Abs. 1 UStG [Q6] |
| SaaS/Hosting/Beratung eines Nicht-EU-Anbieters | **Kz 84/85** + **Kz 67** | „Andere Leistungen" nach § 13b Abs. 2 UStG [Q6] |
| Bau-/Montageleistung eines Subunternehmers (`material.fremdleistungen`) | **Kz 84/85** + **Kz 67** | Bauleistung nach § 13b Abs. 2 Nr. 4 UStG — **prüfen**, ob die Voraussetzungen vorliegen |
| Bewirtung | **Kz 66** in voller Höhe | Vorsteuer ist auch auf den nicht abziehbaren 30 % abziehbar [Q1, Anleitung zu Zeile 63] |
| Geschenk bis 50 € netto | **Kz 66** | Vorsteuer abziehbar |
| Geschenk über 50 € netto | **keine** | Kein Vorsteuerabzug, weil die Ausgabe nicht abziehbar ist [Q3] |
| Private Kfz-Nutzung | Zeile 13, **Kz 81** | Unentgeltliche Wertabgabe ist steuerpflichtiger Umsatz (Abschnitt A des Vordrucks) [Q2] |
| Löhne, Sozialversicherung, Steuern, Versicherungen, Bankgebühren | **keine** | Nicht steuerbar / steuerfrei / kein Leistungsaustausch |

**Kleinunternehmer.** § 19 UStG befreit Umsätze, wenn der Gesamtumsatz im Vorjahr 25.000 € nicht
überschritten hat und im laufenden Jahr 100.000 € nicht überschreitet [Q5]. Ein Kleinunternehmer
gibt in der EÜR **Bruttobeträge** an und führt keine USt-VA ab; in der EÜR nutzt er Zeile 12
(Kz 111) [Q1, S. 3]. Das Mandanten-Flag dafür ist seit V86 vorhanden (`tax.kleinunternehmer`,
Abschnitt 8): ist es gesetzt, exportiert der Bericht Bruttobeträge und USt 0,00 €.

**Reverse Charge — Merksatz.** Bei § 13b UStG enthält die Eingangsrechnung **keine** USt. Der
Leistungsempfänger erklärt sie in der USt-VA als Steuer (Kz 46/47 bzw. 84/85) und zieht sie im
selben Zeitraum als Vorsteuer (Kz 67). In der EÜR ist der **Nettobetrag** Betriebsausgabe; die
„durchlaufende" USt berührt die EÜR nicht [Q1, Anleitung zu Zeile 57: „In Eingangsrechnungen aus
innergemeinschaftlichen Erwerben und aus Leistungen im Sinne des § 13b UStG sind keine
Vorsteuerbeträge enthalten."].

### 3.2 Lohn und Sozialversicherung

Alle elf `loehne.*`-Kategorien laufen in **Zeile 30** (Kz 120, „Ausgaben für eigenes Personal
(z. B. Gehälter, Löhne und Versicherungsbeiträge)") [Q1, S. 4]. Der amtliche Zeilentext nennt die
Versicherungsbeiträge ausdrücklich — Arbeitgeberanteile zur Sozialversicherung gehören deshalb
**nicht** in Zeile 49, sondern in Zeile 30.

| Position | EÜR | USt-VA | Anmerkung |
|---|---|---|---|
| Gehälter, Löhne, Aushilfen, Auszubildende, Geschäftsführer | 30 | — | Lohnsteuer wird **nicht** hier gebucht, sondern über die Lohnsteuer-Anmeldung abgeführt |
| Arbeitgeberanteile Kranken/Renten/Arbeitslosenversicherung | 30 | — | |
| Berufsgenossenschaft | 30 | — | Alternativ Zeile 49 vertretbar → **prüfen** |
| Pauschalabgaben Minijob | 30 | — | Pauschalsteuer § 40a EStG + SV-Pauschalen |
| Vermögenswirksame Leistungen | 30 | — | |
| Freiwillige Sozialleistungen | 30 | — | |

**Lohnsteuer-Anmeldung.** Sie ist eine **eigene** Anmeldung (monatlich/vierteljährlich) und betrifft
die Abführung der Lohnsteuer, des Solidaritätszuschlags und der Kirchensteuer — **nicht** die
Betriebsausgabe. In der EÜR erscheinen Lohnsteuer und Arbeitnehmeranteile nicht als eigener
Aufwand; Aufwand ist das Bruttogehalt plus Arbeitgeberanteile (Zeile 30). Die konkreten Kennzahlen
der Lohnsteuer-Anmeldung wurden in diesem Lauf **nicht verifiziert** → `prüfen (Quelle fehlt)`.
Für die Lohnbuchhaltung gilt: Lohnsteuer-Anmeldung und EÜR-Zeile 30 müssen betragsmäßig
zusammenpassen (Bruttolohnsumme).

### 3.3 Kraftfahrzeug (1-%-Regelung / Fahrtenbuch)

Der Katalog trennt korrekt zwischen **laufenden Kfz-Kosten** (`fahrzeug.*`) und **Investition**
(`anlagevermoegen.fahrzeuge`). Die EÜR hat für Kfz einen eigenen Block (Zeilen 68–74) und für die
private Nutzung eine **Einnahme** (Zeile 20).

| Position | EÜR-Zeile | Kz | Katalogkategorie |
|---|---|---|---|
| Leasingraten Kfz | 68 | 144 | `fahrzeug.leasing` |
| Kfz-Steuer, -Versicherung, Maut | 69 | 145 | `fahrzeug.kfz_steuer`, `fahrzeug.versicherung`, `fahrzeug.maut` |
| Reparaturen, Wartung, Treibstoff, Flug, ÖPNV | 70 | 146 | `fahrzeug.kraftstoff`, `.reparatur`, `.wartung`, `.reifen`, `.pflege`, `.parken`, `.strom_laden`, `.miete`, `.sonstiges`, `betriebsausgaben.fahrtkosten`, `.flug` |
| Nutzungseinlage (privates Kfz im Betrieb) | 71 | 147 | `fahrzeug.*` bei privatem Fahrzeug |
| Fahrten Wohnung ↔ Betrieb (tatsächlich) | 72 | 142 − | Kürzungsbetrag |
| Entfernungspauschale | 73 | 176 + | 0,30 €/km für die ersten 20 km, 0,38 € für jeden weiteren km; max. 4.500 € [Q1, Anleitung zu Zeile 73] |
| Nicht abziehbare Beträge | 74 | 139 − | z. B. unangemessene Aufwendungen |
| **Private Kfz-Nutzung** | **20** | **106** | `fahrzeug.privatnutzung` — **Einnahme**, keine Ausgabe |

**1-%-Regelung.** Wird ein betriebliches Kfz privat genutzt, ist der privat veranlasste Teil als
Betriebseinnahme zu erfassen (Zeile 20). Bemessungsgrundlage ist der Bruttolistenpreis; für Fahrten
zwischen Wohnung und Betrieb kommt die 0,03-%-Regel je Entfernungskilometer hinzu (bzw. 0,002 % je
Familienheimfahrt) — die Formel steht in der Anleitung zu Zeile 72/73 [Q1, Anleitung S. 9]. Für
Elektro-/Hybridfahrzeuge gilt eine reduzierte Bemessungsgrundlage (§ 6 Abs. 1 Nr. 4 EStG) —
Betragsgrenzen und Voraussetzungen **`prüfen`**.

**Fahrtenbuch.** Statt der Pauschale kann der tatsächliche Aufwand nach ordnungsgemäßem
Fahrtenbuch angesetzt werden; die privaten Fahrten sind dann ebenfalls als Einnahme (Zeile 20) zu
erfassen [Q1, Anleitung S. 9; Q4]. Ein Fahrtenbuch muss zeitnah, geschlossen und nachvollziehbar
geführt werden — die App liefert dafür keine Fahrtenliste, nur die Belege.

**Kostendeckelung.** Der Kürzungsbetrag für Fahrten Wohnung ↔ Betrieb darf die tatsächlichen
Gesamtaufwendungen abzüglich der Privatentnahme nicht übersteigen [Q1, Anleitung zu Zeile 72].

**Wichtig für die Katalogpflege:** `fahrzeug.privatnutzung` ist im Katalog als
`is_business_expense = true` und `requires_review = true` geführt. Das ist richtig (Prüfhinweis),
aber die Zeile ist eine **Betriebseinnahme** — ein Export, der daraus eine Ausgabe macht, wäre
falsch. Siehe Abschnitt 5 (`booking_kind`).

### 3.4 Bewirtung (70 %)

- **70 % abziehbar, 30 % nicht abziehbar** — § 4 Abs. 5 Satz 1 Nr. 2 EStG [Q3]; in der EÜR:
  Zeile 63, **Kz 175** (abziehbar) und **Kz 165** (nicht abziehbar) [Q1, S. 5].
- Die **Vorsteuer ist in voller Höhe abziehbar** (auch auf den nicht abziehbaren 30-%-Anteil),
  sofern die Aufwendungen angemessen und nachgewiesen sind → EÜR Zeile 57, USt-VA Kz 66
  [Q1, Anleitung zu Zeile 63].
- **Nachweis:** schriftliche Angaben zu **Ort, Tag, Teilnehmern und Anlass** der Bewirtung sowie
  zur Höhe der Aufwendungen. Bei Bewirtung in einer Gaststätte genügen Anlass und Teilnehmer; die
  Rechnung ist beizufügen [Q3, § 4 Abs. 5 Satz 1 Nr. 2 Satz 2–3 EStG].
- **Katalog:** `betriebsausgaben.bewirtung` (SKR03 4650 / SKR04 6640) hat `requires_review = false`.
  Das ist zu schwach — ohne Teilnehmerliste ist der Abzug angreifbar. **Empfehlung: auf `true`
  setzen** (siehe 6.3).

### 3.5 Geschenke (50 €-Grenze)

- Abziehbar nur, wenn die Anschaffungs-/Herstellungskosten der dem Empfänger **im
  Gewinnermittlungszeitraum** zugewendeten Gegenstände **50 € nicht übersteigen** — § 4 Abs. 5
  Satz 1 Nr. 1 EStG [Q3]; EÜR Zeile 62, **Kz 174** (abziehbar) / **Kz 164** (nicht abziehbar)
  [Q1, S. 5].
- Über 50 €: **kein Betriebsausgabenabzug und kein Vorsteuerabzug**. Die Ausgabe ist in der
  linken Spalte (Kz 164) zu erklären, nicht einfach zu unterschlagen.
- **Empfänger** müssen aus dem Beleg oder der Aufzeichnung ersichtlich sein. Bei typischen
  Werbeartikeln (Taschenkalender, Kugelschreiber) darf auf die Namensangabe verzichtet werden,
  wenn die Grenze offensichtlich nicht überschritten wird [Q1, Anleitung zu Zeile 62].
- **Pauschalsteuer** nach § 37b EStG kann auf die Geschenke entfallen; sie ist Teil der
  abziehbaren Aufwendungen, soweit die 50-€-Grenze eingehalten ist [Q1, Anleitung zu Zeile 62].
- **Katalog:** `betriebsausgaben.geschenke` (SKR03 4630 / SKR04 6610), `requires_review = false` →
  **Empfehlung: `true`** und ein Pflichtfeld „Empfänger" (siehe 6.3).

### 3.6 Abschreibungen (AfA), GWG und Sammelposten

Der Katalog hat für Investitionen drei Ebenen: Aktivierung (`anlagevermoegen.*`), GWG
(`anlagevermoegen.gwg`) und Sammelposten (`anlagevermoegen.gwg_sammelposten`). In der EÜR ist der
Zugang **kein** Aufwand — der Aufwand entsteht über die AfA.

| Instrument | EÜR-Zeile | Kz | Regel |
|---|---|---|---|
| AfA Gebäude | 31 | 136 | Übertrag aus Anlage AVEÜR Zeile 22 [Q1, S. 4] |
| AfA immaterielle WG (Software, Lizenzen) | 32 | 131 | AVEÜR Zeile 37 |
| AfA bewegliche WG (Maschinen, Kfz, BGA, IT) | 33 | 130 | AVEÜR Zeile 63 |
| Sonder-AfA § 7b / § 7g Abs. 5, 6 | 34 | 134 | bis zu insgesamt **40 %** für ab 01.01.2024 angeschaffte bewegliche WG (20 % für ältere), verteilt auf das Jahr der Anschaffung und die vier Folgejahre; Gewinngrenze 200.000 € [Q1, Anleitung S. 5] |
| Herabsetzungsbeträge § 7g Abs. 2 Satz 3 | 35 | 138 | |
| **GWG § 6 Abs. 2** | **36** | **132** | sofort abziehbar bis **800 € netto** je WG [Q4]; Verzeichnis bei > 250 € |
| **Sammelposten § 6 Abs. 2a** | **37** | **137** | **250 € bis 1.000 €** netto, Auflösung über 5 Jahre (je 1/5) [Q4] |
| Restbuchwert ausgeschiedener WG | 38 | 135 | Verkauf/Entnahme/Verschrottung |

**Wichtig:** Die **Anlage AVEÜR** (Anlageverzeichnis) ist notwendiger Bestandteil der
Einnahmenüberschussrechnung [Q1, S. 2]. Ohne AVEÜR ist die AfA nicht belegbar. Für digitale
Wirtschaftsgüter (Hardware und Standardsoftware) kann eine Nutzungsdauer von **einem Jahr**
angenommen werden; sie sind trotzdem im AVEÜR zu erfassen (BMF-Schreiben vom 22.02.2022,
BStBl I S. 187, zitiert in [Q1, Anleitung S. 5]).

**Kataloghinweis:** `anlagevermoegen.gwg` nutzt SKR03 **4855** / SKR04 **6260**, `gwg_sammelposten`
**4862** / **6264** — beides Aufwandskonten, konsistent mit der Sofortabschreibung. Die Konten sind
nicht gegen ein DATEV-Original verifiziert → `prüfen`.

### 3.7 Privatentnahmen (keine Betriebsausgabe)

Die acht `privatentnahme.*`-Kategorien sind im Katalog bereits als **nicht betrieblich** markiert
(`is_business_expense = false`, `requires_review = true`, Warnung in der Seed-Migration V81).

- Sie dürfen **nicht** in den Zeilen 24–75 erscheinen.
- Geld- und Warenentnahmen sind **Entnahmen** und werden in Zeile **106** (Kz 122) erklärt; Einlagen
  in Zeile 107 (Kz 123) [Q1, S. 5]. Beides beeinflusst den Gewinn **nicht**.
- Private Lebenshaltung, private Versicherungen, Einkommensteuer und Urlaubsreisen sind **gar
  keine** EÜR-Position — sie tauchen in der Anlage EÜR nicht auf.
- **Warenentnahme** löst Umsatzsteuer auf unentgeltliche Wertabgabe aus (Bemessungsgrundlage
  Zeile 13, Kz 81 der USt-VA) [Q2].
- Überschreiten die Entnahmen Gewinn + Einlagen, entstehen **Überentnahmen**; die Schuldzinsen
  sind dann nach § 4 Abs. 4a EStG nur beschränkt abziehbar (Anlage SZ) [Q1, Anleitung zu Zeile 56].

### 3.8 Zusammenfassende Meldung (ZM) — nicht einschlägig

Die ZM betrifft **Ausgangsleistungen** an Unternehmer im EU-Ausland (innergemeinschaftliche
Lieferungen und sonstige Leistungen), nicht Eingangsrechnungen. Der Ausgabenkatalog enthält keine
Erlöse; die ZM ist daher für dieses Mapping **nicht relevant**. Sobald das CRM Ausgangsrechnungen
exportiert, braucht es dafür ein eigenes Mapping (USt-VA Kz 41 bzw. Zeile 30 der USt-VA).
`prüfen (Quelle fehlt)` für die konkreten ZM-Felder — in diesem Lauf nicht untersucht.

---

## 4. Wo finde ich das in ELSTER (Mein ELSTER)

> Die Menübezeichnungen in Mein ELSTER ändern sich mit Releases. Die Pfade unten sind der
> verifizierte Einstieg [Q11]; die letzten Klicks vor dem Absenden **am Bildschirm prüfen**
> (`prüfen`).

### 4.1 Anlage EÜR

1. Mein ELSTER öffnen und anmelden (Zertifikat/ELSTER-Secure).
2. **Formulare & Leistungen → Alle Formulare** [Q11].
3. Nach **„EÜR"** suchen → **„Einnahmenüberschussrechnung (Anlage EÜR)"** wählen.
4. **Kalenderjahr** prüfen (Zeile 3/4 des Vordrucks) und **Art des Betriebs**, **Rechtsform**,
   **Einkunftsart** (Kz 103: 1 = LuF, 2 = Gewerbe, 3 = Selbständige Arbeit) ausfüllen.
5. Seite 1: **Betriebseinnahmen**, Zeilen 12–23. Unsere Kategorien liefern hier nur
   `fahrzeug.privatnutzung` → Zeile 20 (Kz 106).
6. Seite 2: **Betriebsausgaben**, Zeilen 24–75. Reihenfolge im Formular:
   Waren (27) → Fremdleistungen (29) → Personal (30) → AfA (31–38) → Raumkosten (39–42) →
   sonstige (43–60) → beschränkt abziehbare (62–67) → Kfz (68–74) → Summe (75).
7. Seite 3: **Ermittlung des Gewinns** (76–97) — rechnet ELSTER weitgehend selbst.
8. **Anlage AVEÜR** separat öffnen und das Anlageverzeichnis befüllen (Pflichtbestandteil,
   Voraussetzung für Zeilen 31–38) [Q1, S. 2].
9. Bei Personengesellschaften zusätzlich **Anlage FE 1/FG**; bei Einzelunternehmen ggf.
   **Anlage SZ** (Schuldzinsen, § 4 Abs. 4a EStG) und die Entnahmen/Einlagen in Zeilen 106/107.
10. **Prüfen → Senden**; den Bescheid/Übertragungsprotokoll archivieren.

**Typische Fehlerquelle:** Zeile 62/63 haben zwei Betragsspalten. Wer nur den abziehbaren Teil
einträgt und den nicht abziehbaren weglässt, erklärt die Aufwendung unvollständig.

### 4.2 Umsatzsteuer-Voranmeldung

1. **Formulare & Leistungen → Alle Formulare → „Umsatzsteuer-Voranmeldung"**.
2. **Voranmeldungszeitraum** wählen (Monat/Quartal; Dauerfristverlängerung beachten).
3. Abschnitt **A** (Zeilen 13–18): steuerpflichtige Umsätze — für uns nur die unentgeltliche
   Wertabgabe aus privater Kfz-/Warennutzung (Zeile 13, Kz 81).
4. Abschnitt **C** (Zeilen 25–29): innergemeinschaftliche Erwerbe — Kz 89 (19 %) bzw. 93 (7 %).
5. Abschnitt **D** (Zeilen 30–32): § 13b UStG — Kz 46/47 (EU-sonstige Leistung) bzw. 84/85
   (andere Leistungen) [Q2, S. 403].
6. Abschnitt **F** (Zeilen 38–44): Vorsteuer — **Kz 66** (Rechnungen), **Kz 61** (ig. Erwerb),
   **Kz 62** (Einfuhr), **Kz 67** (§ 13b) [Q2, S. 404].
7. Zeile 50 (**Kz 83**): verbleibende Vorauszahlung/Überschuss — dieser Betrag ist später in der
   EÜR Zeile 58 zu erfassen.

### 4.3 Lohnsteuer-Anmeldung

1. **Formulare & Leistungen → Alle Formulare → „Lohnsteuer-Anmeldung"**.
2. Anmeldungszeitraum wählen (monatlich/vierteljährlich; Dauerfristverlängerung möglich).
3. Bruttolohnsumme, Lohnsteuer, Solidaritätszuschlag, Kirchensteuer je Arbeitnehmer eintragen.
4. Die **Kennzahlen dieses Vordrucks** wurden hier nicht verifiziert → `prüfen (Quelle fehlt)`.
5. Abstimmung: Bruttolohnsumme der Lohnsteuer-Anmeldung = Summe der Kategorien `loehne.*`
   (Zeile 30 der EÜR, ohne Arbeitgeberanteile, die separat in Zeile 30 laufen).

---

## 5. Feldkarte: CRM → DATEV-Export → EÜR

**Ist-Zustand (nach V86, verifiziert im Schema):**

| CRM-Feld | Tabelle | Bedeutung |
|---|---|---|
| `datev_skr03`, `datev_skr04` | `expense_categories` | Basiskonten des Katalogs |
| `euer_line`, `euer_kz` | `expense_categories` | Zielzeile und Kennzahl der Anlage EÜR 2025 (seit V86; Zeilen mit zwei Betragsspalten tragen beide Kz, z. B. `165/175`) |
| `is_business_expense` | `expense_categories` | `false` = keine Betriebsausgabe (Privatentnahme) |
| `requires_review` | `expense_categories` | Warnung vor automatischer Buchung |
| `warning` (jsonb, de/en/ru) | `expense_categories` | Anzeigetext für die UI |
| `expense_category_id`, `folder_path`, `expense_category_confidence`, `expense_category_source`, `expense_category_rationale`, `categorized_at` | `documents` | Ergebnis der Kategorisierung |

**Ziel-Felder.** Die folgende Karte ist die fachliche Herleitung des Exports. Mit V86 stehen
`euer_line`/`euer_kz` am Katalog; `booking_kind`, die Beträge und die Vorsteuer-Regel leitet
`ElsterExportService` aus Kategorie, Rechnung und Beleg ab. `ust_satz`, `afa_*` und `datev_konto`
(Override) sind noch nicht Teil des Exports — sie bleiben der Ausbaustufe bzw. dem Mandanten
vorbehalten.

| Zielfeld | Typ | Quelle / Regel | DATEV-Exportspalte (Vorschlag) | EÜR-Ziel |
|---|---|---|---|---|
| `netto_betrag` | numeric | `brutto / (1 + ust_satz)`; bei Kleinunternehmer = brutto | Umsatz (Netto) | Zeilen 27–70 |
| `brutto_betrag` | numeric | Belegwert | Umsatz (Brutto) | Kontrollgröße |
| `ust_satz` | numeric | 19 / 7 / 0 aus Beleg | — | USt-VA Kz 81/86/87 |
| `ust_betrag` | numeric | `brutto − netto` | — | USt-VA Kz 81/86 |
| `vorsteuer_abziehbar` | boolean | § 15 UStG; `false` bei Geschenk > 50 €, Privatentnahme | BU-Schlüssel | EÜR Zeile 57 / USt-VA Kz 66 |
| `vorsteuer_betrag` | numeric | `ust_betrag` wenn abziehbar, sonst 0 | — | Zeile 57 |
| `datev_konto` | varchar | Override je Mandant, sonst `expense_categories.datev_skr03` bzw. `_skr04` | Kontonummer | — |
| `booking_kind` | enum | `AUSGABE`, `EINNAHME`, `ENTNAHME`, `AFA`, `GWG` | Soll/Haben-Kennzeichen | steuert die Zielzeile (Zeile 20 = EINNAHME, 106 = ENTNAHME, 31–38 = AFA) |
| `afa_nutzungsdauer` | int (Jahre) | AfA-Tabelle / BMF 22.02.2022 | — | Zeilen 31–33 |
| `afa_betrag` | numeric | `(AK − Restwert) / Nutzungsdauer` | — | Zeilen 31–33 |
| `euer_zeile` | abgeleitet | Mapping aus Abschnitt 2 + `booking_kind` | — | Zielzeile |
| `euer_kz` | abgeleitet | aus Abschnitt 1.1 | — | Kennzahl |

**Ableitungsregeln**

1. `is_business_expense = false` → kein Export in die EÜR-Ausgabenzeilen; nur `booking_kind = ENTNAHME`
   mit Zeile 106 zulässig.
2. `booking_kind = EINNAHME` → Zeile 20 (private Kfz-Nutzung) bzw. Zeile 17/19 der EÜR; niemals
   eine Ausgabenzeile.
3. `booking_kind = AFA` oder `GWG` → Zeilen 31–38; der Beleg ist der AfA-Nachweis, nicht die
   Anschaffung.
4. `vorsteuer_abziehbar = false` → Betrag **brutto** in die EÜR-Zeile; Zeile 57 bleibt leer.
5. `ust_kz = 67` → die Eingangsrechnung enthält keine USt; `netto = brutto`.
6. Bei `bewirtung` und `geschenke` immer **zwei** Beträge erzeugen (abziehbar/nicht abziehbar),
   auch wenn einer 0,00 € ist — sonst fehlt die Erklärung in Zeile 62/63.

**DATEV-Buchungsstapel.** Der eigentliche DATEV-Export (EXTF-Format) ist **nicht** Teil dieses
Mappings. Spaltennamen, BU-Schlüssel und die Pflichtfelder des Buchungsstapels sind gegen die
DATEV-Dokumentation zu prüfen → `prüfen (Quelle fehlt)`. Die Spalte „DATEV-Exportspalte" oben ist
ein fachlicher Vorschlag, kein DATEV-Zitat.

---

## 6. Prüfliste vor der Abgabe / offene Punkte

### 6.1 DATEV-Konten (Katalogwerte, nicht DATEV-verifiziert)

Die Konten in V81 sind ausdrücklich **Startwerte** (Kommentar in der Migration: „SKR03 and SKR04
are published by DATEV and a client's chart can differ from the standard edition"). Gegen die
öffentliche SKR03-Übersicht [Q10] ließen sich bestätigen: **0080** (Bauten), **0320** (PKW),
**0400** (Betriebsausstattung), **0440** (Werkzeuge), **1800** (Privatentnahmen), **1776**
(Umsatzsteuer 19 %), **1576/1571** (Vorsteuer), **1771** (USt 7 %).

**Nicht bestätigt und daher zu prüfen:** `0210` (Katalog: Maschinen — die Übersicht nennt `0200`
Technische Anlagen und Maschinen), `0410`, `0420`, `0490`, `0027`, `4862`, `4595`, `4710`,
`4955`, `4964`, `4969`, `4228`, `4397`. Ein DATEV-Original-Kontenrahmen lag in diesem Lauf nicht
vor [Q8][Q9] → **`prüfen (Quelle fehlt)`**.

### 6.2 Mandanten-Merkmale (seit V86 vorhanden)

| Merkmal | Warum kritisch | Schlüssel / Wirkung im Export |
|---|---|---|
| Kleinunternehmer-Flag (§ 19 UStG) | entscheidet netto vs. brutto und ob eine USt-VA abgegeben wird [Q5] | `tax.kleinunternehmer` (boolean, Standard `false`): `true` → `netto = brutto`, `ust = 0`, keine Vorsteuer |
| Besteuerungsart (Soll/Ist-Versteuerung) | USt-VA-Zeitraum und Vorsteuerabzug | `tax.soll_ist` (`SOLL`/`IST`, Standard `SOLL`): wird als Angabe exportiert; die zeitliche Zuordnung von Zahlungen bleibt Handarbeit |
| Wirtschaftsjahr ≠ Kalenderjahr | Zeile 4 der EÜR | `tax.wirtschaftsjahr` (Jahr, Standard = laufendes Kalenderjahr): bestimmt den Zeitraum, wenn kein `year` übergeben wird |
| Bewirtung 70/30 | Zeile 63 hat zwei Betragsspalten | `tax.bewirtung_split` (boolean, Standard `true`): 70 % Kz 175 / 30 % Kz 165 |

Die Werte sind Erklärungen des Mandanten, keine Steuerberatung. Sie werden als Mandanten-Overrides in
`system_settings` gespeichert (mit Version und Begründung, wie die übrigen Regeln) und vom Export nur
gelesen — er rät keinen davon.

### 6.3 Kategorien mit zu schwacher Prüfung

`betriebsausgaben.bewirtung` und `betriebsausgaben.geschenke` stehen auf `requires_review = false`.
Beide benötigen zwingend Zusatzangaben (Teilnehmer/Anlass bzw. Empfänger) — **Empfehlung:
`requires_review = true`** und ein Pflichtfeld im Dokument. Das ist eine Katalogänderung, keine
Dokumentänderung; sie gehört in eine neue Migration, nicht in diese Doku.

### 6.4 Fachlich zu prüfende Einzelzuordnungen

| Kategorie | Frage | Status |
|---|---|---|
| `loehne.berufsgenossenschaft` | Zeile 30 (Personal) oder Zeile 49 (Versicherungen)? | `prüfen` |
| `steuern.sonstige_betriebssteuern` | je Steuerart abziehbar (60) oder nicht (67)? | `prüfen` |
| `betriebsausgaben.beratung` | Unternehmensberatung → 60; nur Rechts/Steuer → 46 | `prüfen` |
| `betriebsausgaben.nebenkosten_raum` | Zeile 41 (sonstige Grundstücksaufwendungen) oder Zeile 39 (Miete/Pacht)? | `prüfen` |
| `fahrzeug.maut` | Vorsteuerabzug bei pauschaler Lkw-Maut | `prüfen` |
| `betriebsausgaben.arbeitskleidung` | typische Berufskleidung vs. bürgerliche Kleidung | `prüfen` |
| `anlagevermoegen.bueromoebel` | Nutzungsdauer 13 Jahre (AfA-Tabelle) | `prüfen` |

### 6.5 Abgabereihenfolge (Empfehlung)

1. Belege kategorisieren (V81-Katalog) → `expense_category_id` gesetzt.
2. Netto/USt/Vorsteuer je Beleg bestimmen (Ziel-Felder aus Abschnitt 5).
3. USt-VA aus den Vorsteuer-/USt-Summen (Kz 66/67/61/89/81).
4. Lohnsteuer-Anmeldung aus der Lohnbuchhaltung.
5. Anlage AVEÜR aus den aktivierten Zugängen.
6. Anlage EÜR aus dem Mapping (Abschnitt 2) — Zeilen 27–75.
7. Gegenprobe: Summe Zeile 75 = Summe aller Ausgabenkategorien ohne Privatentnahmen und ohne
   Aktivierungen.

---

## 7. Maschinenlesbare Fassung

`docs/ELSTER_CATEGORIES.csv` enthält dieselben 112 Zeilen mit den Spalten

```
code;parent;name_de;euer_line;euer_label;datev_skr03;datev_skr04;ust_kz;notes
```

`euer_line` ist entweder eine Zeilennummer des Vordrucks 2025, `GRUPPE` (Wurzelknoten) oder
`n/a …` (bewusst keine Betriebsausgabe). Die Datei ist UTF-8, Semikolon-getrennt, Felder mit
Sonderzeichen sind gequotet. Kurzanleitung für den Buchhalter: `docs/ELSTER_README.md`.

---

## 8. Was jetzt automatisiert ist — und was Handarbeit bleibt

### 8.1 Automatisiert (V86 + `GET /api/v1/exports/elster`)

- **EÜR-Zeile und Kennzahl je Kategorie.** V86 ergänzt `expense_categories.euer_line` und
  `euer_kz` und seedet beide aus der geprüften Fassung in `docs/ELSTER_CATEGORIES.csv`. Der Export
  liest sie aus der Datenbank; eine Kontenrahmen-Korrektur bleibt eine Datenänderung, kein Release.
- **Zeitfilter.** `year=YYYY` wählt Belege mit `documents.document_date` im Kalenderjahr. Ohne
  `year` gilt `tax.wirtschaftsjahr`, sonst das laufende Kalenderjahr.
- **Beträge.** Vorrang hat die aus dem Beleg gelesene Rechnung (`invoices.net_amount`,
  `tax_amount`, `gross_amount`); sonst werden `document_extractions` (`Netto`,
  `Umsatzsteuer`, `Brutto`/`Gesamtbetrag`/`Betrag`) gelesen. Fehlt jede Angabe, entsteht eine
  Zeile mit 0,00 € **und** einer Warnung — der Beleg verschwindet nicht.
- **`booking_kind` und Zielzeile.**
  - `fahrzeug.privatnutzung` → `EINNAHME`, Zeile **20**, Kz **106**; der Betrag zählt **nicht** zu
    den Ausgaben.
  - `privatentnahme.*` → `ENTNAHME`; `geld`/`waren` Zeile 106/Kz 122, alle übrigen ohne EÜR-Zeile.
    Kein Ausgabenposten.
  - `anlagevermoegen.*` (außer GWG/Sammelposten) und `betriebsausgaben.abschreibungen` → `AFA`;
    der Beleg ist eine Investition, keine Betriebsausgabe. GWG/Sammelposten → `GWG` und zählen als
    Sofortabzug (Zeilen 36/37).
  - alles Übrige → `AUSGABE`.
- **Bewirtung.** Bei `tax.bewirtung_split=true` werden 70 % (`bewirtung_abziehbar_70`, Kz 175) und
  30 % (`bewirtung_nicht_abziehbar_30`, Kz 165) getrennt ausgewiesen. Die Vorsteuer bleibt in voller
  Höhe abziehbar (EÜR Zeile 57 / USt-VA Kz 66).
- **Geschenke.** Ein Beleg über 50,00 € netto wird als nicht abziehbar exportiert (Kz 164, keine
  Vorsteuer) und mit `GIFT_OVER_50_SINGLE` gewarnt. Zusätzlich summiert der Export je Empfänger und
  Jahr und warnt mit `GIFT_OVER_50_RECIPIENT`, wenn die 50-€-Grenze überschritten ist. Das ist eine
  **Warnung, keine Sperre**: alle Belege bleiben Zeilen des Exports.
- **Kleinunternehmer.** `tax.kleinunternehmer=true` setzt `netto = brutto`, `ust = 0,00` und
  `vorsteuer_abziehbar = false`. Eine USt-Voranmeldung ist dann nicht Teil des Workflows.
- **Leerer Jahrgang.** Ein Jahr ohne kategorisierte Belege antwortet mit **200**, leerer `rows`-Liste
  und `summary` (0 Zeilen, 0,00 €). Das gilt für JSON und CSV.
- **CSV-Spalten** (Semikolon, UTF-8, deutsches Dezimalkomma):
  `category_code; euer_line; euer_kz; datev_skr03; datev_skr04; booking_kind; netto; ust; brutto;
  vorsteuer_abziehbar; bewirtung_abziehbar_70; bewirtung_nicht_abziehbar_30; document_id; date`.
  `booking_kind` ist `AUSGABE`, `EINNAHME`, `ENTNAHME`, `AFA` oder `GWG`; boolesche Werte sind
  `true`/`false`. Das JSON enthält zusätzlich `settings` und `summary` samt `warnings`;
  `summary` liefert `rows`, `netto/ust/brutto` (alle Zeilen), `expensesNetto/Ust/Brutto` (nur
  Ausgaben mit EÜR-Zeile), `vorsteuerAbziehbar`, `bewirtungAbziehbar70`,
  `bewirtungNichtAbziehbar30`, `incomeNetto`, `entnahmeNetto`, `investmentsNetto` und `warnings`.
- **Berechtigung.** `finance.view` **oder** `documents.view` (die Exportdatei enthält
  Finanzdaten, aber der Belegzugriff ist die zweite zulässige Tür).

### 8.2 Bleibt Handarbeit (bewusst nicht automatisiert)

- **Übermittlung an ELSTER.** Der Export ist eine Arbeitsdatei (JSON/CSV), kein ERiC-Datensatz und
  keine Übermittlung. Anlage EÜR, USt-VA, Lohnsteuer-Anmeldung und ZM werden weiterhin in Mein
  ELSTER erstellt und gesendet.
- **Anlage AVEÜR und AfA.** Der Export markiert Investitionen als `AFA`, berechnet aber weder
  Nutzungsdauer noch AfA-Betrag, GWG-Grenzen oder Sammelposten. Das Anlageverzeichnis ist
  Pflichtbestandteil und bleibt manuell.
- **Bewirtungsnachweis.** Ort, Tag, Teilnehmer und Anlass stehen nicht im Belegfeld. Die
  Kategorien `bewirtung`/`geschenke` stehen weiterhin auf `requires_review = false`; die Empfehlung
  aus 6.3 (auf `true` setzen und ein Pflichtfeld einführen) ist **nicht** umgesetzt.
- **Geschenke-Empfänger.** Die Jahresgrenze wird nur gewarnt, nicht durchgesetzt: ob zwei Belege
  denselben Empfänger betreffen, sagt der Export aus `documents.recipient`; fehlt der
  Empfänger, bleibt die Prüfung beim Buchhalter.
- **Reverse Charge / EU-Erwerb.** Die Zuordnung zu Kz 46/47, 84/85, 89 und 61 folgt weiterhin der
  fachlichen Tabelle in 3.1; sie wird nicht aus dem Beleg abgeleitet.
- **1-%-Regelung / Fahrtenbuch.** `fahrzeug.privatnutzung` wird als Einnahme exportiert, aber der
  Betrag muss aus der 1-%-Regel oder dem Fahrtenbuch kommen; die App berechnet ihn nicht.
- **Soll/Ist.** `tax.soll_ist` wird gespeichert und ausgewiesen; eine zahlungsbasierte
  Periodenabgrenzung (IST) ist nicht implementiert.
- **DATEV-Buchungsstapel (EXTF).** Der Export liefert Konten und BU-relevante Angaben, aber keinen
  EXTF-Buchungsstapel. Spaltennamen und BU-Schlüssel sind weiterhin gegen DATEV zu prüfen
  (`prüfen (Quelle fehlt)`).
- **Kontenprüfung.** Die SKR03/SKR04-Konten bleiben Startwerte aus V81; die offenen Punkte aus 6.1
  gelten unverändert.
