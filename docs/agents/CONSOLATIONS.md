# Workflow-Vorlagen (Konstellationen)

Eine **Konstellation** ist eine benannte, geordnete Folge von Katalog-Agenten, die eine wiederkehrende Aufgabe eines Unternehmens löst. Die Vorlagen liegen als JSON unter `services/api/src/main/resources/agent-constellations/*.json`, werden beim Start validiert und über die API gelesen. Jeder Schritt verweist auf eine **existierende** Agenten-ID; ein Schritt, dessen Agent noch Konzept ist, wird als solcher ausgewiesen.

**30 Vorlagen, 151 Schritte, Branchen von Handwerk bis Pflege.**

## Übersicht

| # | ID | Name (DE / RU) | Für wen | Schritte | Ergebnis | Zeitgewinn |
|---|---|---|---|---|---|---|
| 1 | `access-review` | IT-Zugriffsprüfung / Проверка ИТ-доступов | it, finance, healthcare, services, agency | `it-access-review` → `it-backup-check` → `it-patch-window` → `compliance-check` → `audit-trail` | Prüfliste; Patch-Plan; Prüfprotokoll | ca. 2 Std./Woche |
| 2 | `audit-prep` | Audit-Vorbereitung / Подготовка к аудиту | services, healthcare, manufacturing, finance, ngo | `legal-audit-prep` → `compliance-check` → `audit-trail` → `risk-flag` → `retention` | Audit-Ordner; Abweichungsbericht; Risikoliste | ca. 4 Std./Woche |
| 3 | `bank-reconciliation` | Bankabgleich / Сверка с банком | services, handwerk, ecommerce, manufacturing, realestate | `banking-statement-import` → `banking-reconcile` → `payment-match` → `invoice-check` → `account-datev` | Abgleichsliste; Buchungsstapel; Abweichungsbericht | ca. 3 Std./Woche |
| 4 | `budgeting` | Budgetplanung / Бюджетирование | services, manufacturing, agency, handwerk, ngo | `budget-watch` → `cashflow` → `expense-sort` → `report-daily` → `insight` | Budgetabweichungen; Liquiditätsprognose; Maßnahmenliste | ca. 2 Std./Woche |
| 5 | `content-plan` | Marketing-Redaktionsplan / Контент-план маркетинга | agency, ecommerce, services, handwerk, retail | `marketing-seo-audit` → `marketing-content-brief` → `marketing-social-calendar` → `marketing-newsletter` → `knowledge-kpi-report` | Redaktionsplan; Content-Briefings; Newsletter-Entwurf; KPI-Bericht | ca. 4 Std./Woche |
| 6 | `contract-control` | Vertragskontrolle / Контроль договоров | services, realestate, manufacturing, consulting, handwerk | `legal-contract-control` → `legal-nda-check` → `deadline-watch` → `task-dispatch` → `doc-sign` | Fristenübersicht; NDA-Prüfbericht; Aufgaben mit Zuständigkeit | ca. 2 Std./Woche |
| 7 | `customer-onboarding` | Kunden-Onboarding / Онбординг клиента | services, agency, it, consulting, handwerk | `crm-lead` → `crm-contact` → `crm-deal` → `task-dispatch` → `letter-draft` → `calendar-sync` | Vollständiger Kundenkontakt; Offener Deal mit Aufgaben; Bestätigung und Termin | ca. 2 Std./Woche |
| 8 | `customs-clearance` | Zollabwicklung / Таможенное оформление | logistics, manufacturing, ecommerce, transport | `logistics-customs` → `logistics-delivery-tracking` → `doc-detect` → `doc-extract` → `doc-archive` | Zollanmelde-Entwurf; Terminübersicht; Archivierte Zollbelege | ca. 2 Std./Woche |
| 9 | `datev-pipeline` | DATEV-Konveyor / Конвейер DATEV | handwerk, ecommerce, manufacturing, services, tax | `doc-ocr` → `doc-extract` → `invoice-capture` → `invoice-check` → `account-datev` → `doc-datev` | DATEV-Exportordner; Abweichungsbericht; Prüfprotokoll | ca. 4 Std./Woche |
| 10 | `dunning-cycle` | Mahnlauf / Цикл напоминаний об оплате | handwerk, services, ecommerce, manufacturing | `dunning` → `letter-draft` → `mail-reply` → `crm-activity` | Mahnliste; Mahnschreiben-Entwürfe; Aktualisierte Kontakthistorie | ca. 1 Std./Woche |
| 11 | `ecommerce-orders` | Online-Bestellungen / Заказы интернет-магазина | ecommerce, retail | `ecommerce-order-sync` → `ecommerce-payment-check` → `logistics-order-intake` → `support-ticket` → `report-daily` | Bestellliste; Fulfillment-Aufträge; Tagesbericht; Support-Tickets | ca. 4 Std./Woche |
| 12 | `employee-training` | Schulungen und Nachweise / Обучение и подтверждения | handwerk, healthcare, it, manufacturing, services | `hr-training` → `hr-onboarding` → `calendar-sync` → `task-dispatch` → `knowledge-kpi-report` | Schulungsplan; Nachweisliste; Weiterbildungsbericht | ca. 2 Std./Woche |
| 13 | `facility-maintenance` | Objektwartung / Обслуживание объектов | realestate, handwerk, services, construction | `realestate-maintenance` → `realestate-tenant-admin` → `task-dispatch` → `letter-draft` → `doc-sign` | Auftragsentwurf; Informationsschreiben; Signierter Auftrag | ca. 2 Std./Woche |
| 14 | `gdpr-request` | DSGVO-Anfrage / Запрос по GDPR | services, healthcare, ecommerce, agency, ngo | `legal-gdpr-request` → `gdpr-guard` → `retention` → `doc-redact` → `audit-trail` | Auskunftskopie; Löschplan; Prüfprotokoll | ca. 2 Std./Woche |
| 15 | `handwerk-scheduling` | Handwerk-Terminplanung / Планирование работ Handwerk | handwerk, construction, manufacturing | `production-scheduling` → `production-work-order` → `production-material-plan` → `calendar-sync` → `task-dispatch` | Terminplan; Fertigungsaufträge; Materialliste; Kundentermine | ca. 3 Std./Woche |
| 16 | `inbox-triage` | Postfach-Triage / Разбор почты и распределение | handwerk, services, logistics, realestate | `mail-sort` → `mail-accept` → `doc-detect` → `doc-extract` → `doc-assign` | Sortiertes Postfach; Dokumente mit extrahierten Feldern; Zugeordnete Vorgänge | ca. 3 Std./Woche |
| 17 | `incident-response` | IT-Störung / ИТ-инцидент | it, agency, ecommerce, logistics, services | `it-incident` → `monitor-systems` → `support-escalation` → `task-dispatch` → `report-daily` | Incident-Protokoll; Eskalationsliste; Tagesbericht | ca. 2 Std./Woche |
| 18 | `invoice-approval` | Rechnungsfreigabe / Согласование счетов | services, handwerk, manufacturing, realestate, logistics | `invoice-capture` → `invoice-check` → `approval` → `payment-match` → `account-datev` | Freigabeprotokoll; Zahlungsvorschlag; Buchungsstapel | ca. 2 Std./Woche |
| 19 | `kyc-sanctions` | KYC- und Sanktionsprüfung / KYC и проверка санкций | banking, finance, legal, realestate, consulting | `legal-sanctions-kyc` → `crm-contact` → `compliance-check` → `risk-flag` → `approval` | KYC-Prüfprotokoll; Risiko-Hinweis; Dokumentierte Entscheidung | ca. 1 Std./Woche |
| 20 | `lead-to-cash` | Vom Lead zum Auftrag / От лида до заказа | services, agency, it, handwerk, consulting | `marketing-lead-nurture` → `crm-lead` → `crm-deal` → `sales-offer-draft` → `sales-followup-cadence` | Qualifizierter Lead; Angebotsentwurf; Follow-up-Plan | ca. 3 Std./Woche |
| 21 | `monthly-kpi` | Monats-KPI-Bericht / Месячный KPI-отчёт | services, handwerk, ecommerce, manufacturing, agency | `metric-collect` → `report-daily` → `knowledge-kpi-report` → `anomaly` → `insight` | KPI-Bericht; Abweichungsbericht; Maßnahmenempfehlungen | ca. 3 Std./Woche |
| 22 | `nps-loop` | Kundenstimmen und NPS / Голос клиента и NPS | services, ecommerce, gastronomy, handwerk, realestate | `support-nps` → `support-review-response` → `crm-activity` → `insight` → `marketing-content-brief` | NPS-Auswertung; Antwortentwürfe; Content-Briefings | ca. 1 Std./Woche |
| 23 | `practice-recall` | Praxis-Recall / Напоминания в практике | healthcare, therapy, care | `healthcare-recall` → `healthcare-appointment` → `healthcare-documentation` → `calendar-sync` → `task-dispatch` | Recall-Liste; Terminvorschläge; Dokumentationsentwürfe | ca. 1 Std./Woche |
| 24 | `recruiting-pipeline` | Recruiting-Pipeline / Рекрутинг | handwerk, services, healthcare, it, gastronomy | `hr-recruiting-screening` → `calendar-sync` → `task-dispatch` → `letter-draft` → `hr-onboarding` | Shortlist; Interviewplan; Onboarding-Plan | ca. 5 Std./Woche |
| 25 | `reservations` | Reservierungen / Бронирования | gastronomy, hospitality, services | `hospitality-reservation` → `hospitality-no-show` → `calendar-sync` → `mail-reply` → `crm-contact` | Reservierungsliste; Bestätigungen; Gastprofile | ca. 1 Std./Woche |
| 26 | `returns-handling` | Retourenbearbeitung / Обработка возвратов | ecommerce, retail | `ecommerce-order-sync` → `ecommerce-returns` → `support-ticket` → `ecommerce-payment-check` → `mail-reply` | Retourenentscheidung; Kundenantwort; Erstattungsvorschlag | ca. 2 Std./Woche |
| 27 | `shift-planning` | Dienstplanung / Планирование смен | gastronomy, hospitality, healthcare, retail, logistics | `hospitality-shift-plan` → `hr-leave-request` → `hr-time-tracking` → `calendar-sync` → `task-dispatch` | Dienstplan; Konfliktliste; Vertretungsaufgaben | ca. 2 Std./Woche |
| 28 | `supply-chain` | Lieferkette überwachen / Контроль цепочки поставок | manufacturing, logistics, handwerk, ecommerce, retail | `logistics-order-intake` → `logistics-delivery-tracking` → `logistics-inventory-count` → `logistics-supplier-scorecard` → `task-dispatch` | Verzugsliste; Inventurunterlagen; Lieferantenbewertung; Maßnahmenliste | ca. 3 Std./Woche |
| 29 | `warehouse-count` | Lager und Inventur / Склад и инвентаризация | manufacturing, logistics, ecommerce, retail, handwerk | `logistics-inventory-count` → `logistics-delivery-tracking` → `logistics-order-intake` → `task-dispatch` → `knowledge-kpi-report` | Zähllisten; Differenzprotokoll; Inventurbericht | ca. 3 Std./Woche |
| 30 | `warranty-case` | Garantiefälle / Гарантийные случаи | handwerk, manufacturing, retail, ecommerce | `production-warranty` → `crm-contact` → `task-dispatch` → `letter-draft` → `doc-archive` | Garantieentscheidung; Kundenschreiben; Archivierter Nachweis | ca. 1 Std./Woche |

## Details je Vorlage

### `access-review` — IT-Zugriffsprüfung / Проверка ИТ-доступов

Prüft Konten und Rollen auf ungenutzte Zugänge, fehlende MFA und abgelaufene Befristungen. Die Prüfliste ist entscheidungsfertig, entzieht aber nichts automatisch.

*Für wen:* it, finance, healthcare, services, agency. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `it-access-review` | Zugriffe prüfen | Konten, Rollen und letzte Anmeldung | Mitgliedschaften → Prüfliste mit Befunden |
| 2 | `it-backup-check` | Backups prüfen | Läufe und Wiederherstellungsnachweise | Backup-Protokolle → Lückenliste |
| 3 | `it-patch-window` | Patches planen | Schweregrad und Wartungsfenster | Patchliste → Patch-Plan |
| 4 | `compliance-check` | Anforderungen prüfen | Richtlinien und Pflichtnachweise | Prüfliste → Abweichungen |
| 5 | `audit-trail` | Nachweis erzeugen | Prüfung und Entscheidung dokumentieren | Befunde → Prüfprotokoll |

**Ergebnis:** Prüfliste; Patch-Plan; Prüfprotokoll.

### `audit-prep` — Audit-Vorbereitung / Подготовка к аудиту

Stellt Nachweise für ein internes oder externes Audit zusammen, prüft Compliance-Anforderungen und weist Lücken mit Verantwortlichkeit aus. Der Ordner ist prüfbar, weil jede Datei eine Quelle und einen Zeitraum hat.

*Für wen:* services, healthcare, manufacturing, finance, ngo. *Zeitgewinn:* ca. 4 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `legal-audit-prep` | Nachweise sammeln | Richtlinien, Freigaben und Protokolle | Auditauftrag → Nachweisordner |
| 2 | `compliance-check` | Anforderungen prüfen | Pflichtangaben und Verfahren kontrollieren | Arbeitsbereich → Abweichungsbericht |
| 3 | `audit-trail` | Prüfpfad erzeugen | Wer hat wann was geändert | Objekte im Scope → Prüfprotokoll |
| 4 | `risk-flag` | Risiken markieren | Befunde mit Schweregrad | Abweichungen → Risikoliste |
| 5 | `retention` | Aufbewahrung prüfen | Fristen und Legal Holds | Nachweise → Aufbewahrungsübersicht |

**Ergebnis:** Audit-Ordner; Abweichungsbericht; Risikoliste.

### `bank-reconciliation` — Bankabgleich / Сверка с банком

Importiert Kontoauszüge, gleicht Zahlungen mit offenen Rechnungen ab und bereitet die Buchung vor. Mehrdeutige Fälle bleiben offen und werden mit Begründung gelistet, statt geraten zu werden.

*Für wen:* services, handwerk, ecommerce, manufacturing, realestate. *Zeitgewinn:* ca. 3 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `banking-statement-import` | Auszug importieren | Format prüfen und Dubletten erkennen | Kontoauszug → importierte Umsätze |
| 2 | `banking-reconcile` | Abgleichen | Zahlungen und Rechnungen zuordnen | Umsätze und Rechnungen → Abgleichsliste |
| 3 | `payment-match` | Treffer prüfen | Eindeutige Zahlungen bestätigen | Abgleichsliste → bestätigte Zahlungen |
| 4 | `invoice-check` | Abweichungen prüfen | Betrag, Nummer und Lieferant kontrollieren | offene Fälle → Abweichungsbericht |
| 5 | `account-datev` | Buchen vorbereiten | Kontierung und Kontrollsumme | bestätigte Zahlungen → Buchungsstapel |

**Ergebnis:** Abgleichsliste; Buchungsstapel; Abweichungsbericht.

### `budgeting` — Budgetplanung / Бюджетирование

Vergleicht Ist-Ausgaben mit den Budgets, projiziert die Liquidität und bereitet Abweichungen für das Monatsgespräch auf. Der Bericht zeigt Ursachen je Kostenkategorie, keine globale Ampel.

*Für wen:* services, manufacturing, agency, handwerk, ngo. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `budget-watch` | Budgets prüfen | Ist und Plan je Kostenkategorie | Ausgaben und Budgets → Abweichungsbericht |
| 2 | `cashflow` | Liquidität planen | Zu- und Abflüsse rollierend prognostizieren | Rechnungen und Zahlungen → Liquiditätsprognose |
| 3 | `expense-sort` | Ausgaben ordnen | Belege nach Kategorien sortieren | offene Belege → kategorisierte Ausgaben |
| 4 | `report-daily` | Lage zusammenfassen | Offene Posten und Fristen | Buchungen und Aufgaben → Tagesübersicht |
| 5 | `insight` | Empfehlungen | Abweichungen bewerten und Maßnahmen vorschlagen | Berichte → Maßnahmenliste |

**Ergebnis:** Budgetabweichungen; Liquiditätsprognose; Maßnahmenliste.

### `content-plan` — Marketing-Redaktionsplan / Контент-план маркетинга

Verbindet SEO-Befunde, Content-Briefings und den Redaktionsplan zu einem Monatsablauf. Newsletter und Social-Media-Beiträge greifen dieselben Themen auf, statt nebeneinander zu laufen. Der KPI-Bericht zeigt am Monatsende, was gewirkt hat.

*Für wen:* agency, ecommerce, services, handwerk, retail. *Zeitgewinn:* ca. 4 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `marketing-seo-audit` | SEO prüfen | Technische und inhaltliche Lücken finden | Website und Keywords → priorisierte Maßnahmenliste |
| 2 | `marketing-content-brief` | Briefings schreiben | Themen in prüfbare Briefings übersetzen | Maßnahmenliste → Content-Briefings |
| 3 | `marketing-social-calendar` | Redaktionsplan bauen | Beiträge mit Kanal und Termin planen | Briefings und Anlässe → Redaktionsplan |
| 4 | `marketing-newsletter` | Newsletter planen | Ausgabe aus den Themen zusammenstellen | Redaktionsplan → versandfertige Newsletter-Ausgabe |
| 5 | `knowledge-kpi-report` | Wirkung messen | Kennzahlen für den Monat berichten | Kampagnen- und Kanaldaten → KPI-Bericht |

**Ergebnis:** Redaktionsplan; Content-Briefings; Newsletter-Entwurf; KPI-Bericht.

### `contract-control` — Vertragskontrolle / Контроль договоров

Überwacht Laufzeiten und Kündigungsfristen, prüft eingehende NDAs und legt Fristen als Aufgaben an. Verträge werden nicht automatisch gekündigt; der Ablauf stellt sicher, dass die Entscheidung rechtzeitig vorliegt.

*Für wen:* services, realestate, manufacturing, consulting, handwerk. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `legal-contract-control` | Laufzeiten prüfen | Endende Verträge und Pflichten ermitteln | Vertragsregister → Fristenübersicht |
| 2 | `legal-nda-check` | NDA prüfen | Abweichungen von der Musterklausel | eingehendes NDA → Abweichungsliste mit Risiko |
| 3 | `deadline-watch` | Fristen überwachen | Nahende und überfällige Fristen melden | Fristenübersicht → Eskalationsliste |
| 4 | `task-dispatch` | Aufgaben anlegen | Zuständigkeit für Entscheidung festlegen | Fristen und Prüfbefunde → zugewiesene Aufgaben |
| 5 | `doc-sign` | Entscheidung dokumentieren *(optional)* | Kündigung oder Verlängerung zur Signatur | Aufgaben → Signaturvorgang |

**Ergebnis:** Fristenübersicht; NDA-Prüfbericht; Aufgaben mit Zuständigkeit.

### `customer-onboarding` — Kunden-Onboarding / Онбординг клиента

Führt von der ersten Anfrage bis zum ersten Termin: Lead erfassen, Kontakt und Deal anlegen, Aufgaben verteilen, Bestätigung schreiben und Termin eintragen. Der Kunde erhält einen klaren nächsten Schritt, das Team eine vollständige Akte.

*Für wen:* services, agency, it, consulting, handwerk. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `crm-lead` | Lead erfassen | Anfrage als Kontakt mit Quelle anlegen | Anfrage per E-Mail oder Formular → Lead mit Quelle |
| 2 | `crm-contact` | Kontakt anreichern | Fehlende Angaben ergänzen und Dubletten prüfen | Lead → vollständiger Kontakt |
| 3 | `crm-deal` | Deal anlegen | Verkaufschance mit Phase und Wert erzeugen | Kontakt und Anfrage → offener Deal |
| 4 | `task-dispatch` | Aufgaben verteilen | Zuständigkeit und Frist festlegen | Deal → zugewiesene Aufgaben |
| 5 | `letter-draft` | Bestätigung schreiben | Willkommensschreiben mit nächstem Schritt | Kontakt und Deal → Bestätigungsentwurf |
| 6 | `calendar-sync` | Termin eintragen *(optional)* | Erstgespräch im Kalender abstimmen | Kontakt und Team → gebuchter Termin |

**Ergebnis:** Vollständiger Kundenkontakt; Offener Deal mit Aufgaben; Bestätigung und Termin.

### `customs-clearance` — Zollabwicklung / Таможенное оформление

Prüft Sendungsdaten auf Vollständigkeit, bereitet die Zollanmeldung vor und dokumentiert die Belege. Fehlende Tarifnummern oder Widersprüche stoppen den Ablauf vor der Anmeldung.

*Für wen:* logistics, manufacturing, ecommerce, transport. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `logistics-customs` | Sendung prüfen | Warenwert, Ursprung und Tarif kontrollieren | Sendungsdaten → Anmeldeentwurf mit Lücken |
| 2 | `logistics-delivery-tracking` | Termine abstimmen | Liefertermin und Verzollung verbinden | offene Sendungen → Terminübersicht |
| 3 | `doc-detect` | Dokumente erkennen | Handelsrechnung und Ursprungsnachweis zuordnen | Sendungsdokumente → Dokumente mit Typ |
| 4 | `doc-extract` | Angaben auslesen | Werte aus Rechnung und Packliste | Sendungsdokumente → strukturierte Zollangaben |
| 5 | `doc-archive` | Belege archivieren | Zollunterlagen revisionssicher ablegen | Anmeldung → archivierter Zollvorgang |

**Ergebnis:** Zollanmelde-Entwurf; Terminübersicht; Archivierte Zollbelege.

### `datev-pipeline` — DATEV-Konveyor / Конвейер DATEV

Führt Eingangsbelege vom Scan bis zur DATEV-Übergabe: Text erkennen, Felder extrahieren, Rechnung erfassen, prüfen und kontieren. Der Konveyor stoppt bei fehlenden Pflichtangaben, statt unvollständige Buchungen weiterzureichen.

*Für wen:* handwerk, ecommerce, manufacturing, services, tax. *Zeitgewinn:* ca. 4 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `doc-ocr` | Text erkennen | OCR-Bereitschaft prüfen und Text aufbereiten | Scan oder PDF → durchsuchbarer Text |
| 2 | `doc-extract` | Felder auslesen | Nummer, Datum, Betrag und Parteien extrahieren | Dokument mit Text → strukturierte Rechnungsfelder |
| 3 | `invoice-capture` | Rechnung erfassen | Eingangsrechnung mit Duplikatschutz anlegen | extrahierte Felder → erfasste Rechnung |
| 4 | `invoice-check` | Rechnung prüfen | Duplikate, Betragsgrenzen und Gegenpartei prüfen | erfasste Rechnung → geprüfte Rechnung oder Abweichungsbericht |
| 5 | `account-datev` | Kontieren | Kostenkategorie und Kontrollsumme bilden | geprüfte Rechnung → kontierte Buchung |
| 6 | `doc-datev` | DATEV-Paket | Feldzuordnung und Exportmarkierung erzeugen | kontierte Buchungen → DATEV-Exportordner |

**Ergebnis:** DATEV-Exportordner; Abweichungsbericht; Prüfprotokoll.

### `dunning-cycle` — Mahnlauf / Цикл напоминаний об оплате

Erkennt überfällige Ausgangsrechnungen, legt die passende Mahnstufe fest und erstellt Mahnschreiben als Entwurf. Versendet wird erst nach Freigabe; jede Stufe wird am Vorgang dokumentiert.

*Für wen:* handwerk, services, ecommerce, manufacturing. *Zeitgewinn:* ca. 1 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `dunning` | Mahnstufen bestimmen | Überfällige Rechnungen und Stufe ermitteln | Ausgangsrechnungen → Mahnliste mit Stufe |
| 2 | `letter-draft` | Mahnschreiben entwerfen | Vorlage füllen und Dokument erzeugen | Mahnliste → Mahnschreiben als Entwurf |
| 3 | `mail-reply` | Versand vorbereiten | E-Mail-Entwurf je Empfänger anlegen | Mahnschreiben → versandfertige Entwürfe |
| 4 | `crm-activity` | Historie festhalten | Mahnstufe und Versand am Kontakt protokollieren | versandte Mahnungen → vollständige Kontakthistorie |

**Ergebnis:** Mahnliste; Mahnschreiben-Entwürfe; Aktualisierte Kontakthistorie.

### `ecommerce-orders` — Online-Bestellungen / Заказы интернет-магазина

Importiert Bestellungen, prüft Zahlungseingang und stößt Fulfillment an. Problematische Zahlungen werden zu Tickets, damit der Support nicht erst nach der Kundenbeschwerde davon erfährt.

*Für wen:* ecommerce, retail. *Zeitgewinn:* ca. 4 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `ecommerce-order-sync` | Bestellungen importieren | Shop und Marktplätze abgleichen | neue Bestellungen → Bestellliste mit Status |
| 2 | `ecommerce-payment-check` | Zahlung prüfen | Offene und fehlgeschlagene Zahlungen | Bestellliste → Zahlungsstatus mit Vorschlag |
| 3 | `logistics-order-intake` | Fulfillment anstoßen | Auftrag und Lieferung vorbereiten | bezahlte Bestellungen → Fulfillment-Auftrag |
| 4 | `support-ticket` | Problemfälle melden | Zahlungs- und Lieferprobleme als Ticket | auffällige Bestellungen → Support-Tickets |
| 5 | `report-daily` | Tagesbericht | Bestelllage und offene Fälle zusammenfassen | Bestell- und Ticketdaten → Tagesbericht |

**Ergebnis:** Bestellliste; Fulfillment-Aufträge; Tagesbericht; Support-Tickets.

### `employee-training` — Schulungen und Nachweise / Обучение и подтверждения

Ermittelt Pflichtschulungen je Rolle, plant Termine und prüft Nachweise. Fehlende Nachweise bleiben sichtbar, statt als erledigt zu gelten; neue Mitarbeitende werden direkt in den Plan aufgenommen.

*Für wen:* handwerk, healthcare, it, manufacturing, services. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `hr-training` | Bedarf ermitteln | Pflichtschulungen und Fristen je Rolle | Rollen und Fristen → Schulungsplan |
| 2 | `hr-onboarding` | Neue einbeziehen | Onboarding-Schritte mit Schulungen verknüpfen | Startdaten → ergänzter Plan |
| 3 | `calendar-sync` | Termine planen | Schulungstermine mit Anbietern abstimmen | Schulungsplan → Kalendereinträge |
| 4 | `task-dispatch` | Nachweise anfordern | Verantwortliche für Nachweise zuweisen | Schulungsplan → Aufgaben |
| 5 | `knowledge-kpi-report` | Quote berichten | Schulungsstand als Kennzahl | Nachweise → Weiterbildungsbericht |

**Ergebnis:** Schulungsplan; Nachweisliste; Weiterbildungsbericht.

### `facility-maintenance` — Objektwartung / Обслуживание объектов

Nimmt Schadensmeldungen auf, ordnet Gewerke zu und bereitet Aufträge vor. Mieter und Dienstleister erhalten dieselbe nachvollziehbare Frist; der Auftrag startet erst nach Freigabe.

*Für wen:* realestate, handwerk, services, construction. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `realestate-maintenance` | Schaden aufnehmen | Gewerk und Dringlichkeit bestimmen | Schadensmeldung → Auftragsentwurf |
| 2 | `realestate-tenant-admin` | Mieter zuordnen | Objekt, Mieter und Zugang prüfen | Meldung → Vorgang mit Beteiligten |
| 3 | `task-dispatch` | Auftrag zuweisen | Dienstleister und Frist festlegen | Auftragsentwurf → zugewiesener Auftrag |
| 4 | `letter-draft` | Beteiligte informieren | Bestätigung und Termin mitteilen | Auftrag → Schreiben als Entwurf |
| 5 | `doc-sign` | Freigabe einholen *(optional)* | Auftrag oder Vollmacht zur Signatur | Auftrag → Signaturvorgang |

**Ergebnis:** Auftragsentwurf; Informationsschreiben; Signierter Auftrag.

### `gdpr-request` — DSGVO-Anfrage / Запрос по GDPR

Bearbeitet Auskunfts- und Löschbegehren: Datenbestand ermitteln, Auskunftskopie erstellen, Aufbewahrungspflichten prüfen und Löschplan vorlegen. Gelöscht wird erst nach Freigabe und nicht, solange Legal Holds bestehen.

*Für wen:* services, healthcare, ecommerce, agency, ngo. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `legal-gdpr-request` | Anfrage aufnehmen | Art bestimmen und Datenbestand erheben | Anfrage einer Person → Auskunft oder Plan mit Dateninventar |
| 2 | `gdpr-guard` | Datenschutz prüfen | Personenbezug und Löschreife bewerten | Dateninventar → Löschplan mit Vorbehalt |
| 3 | `retention` | Aufbewahrung prüfen | Fristen und Legal Holds abgleichen | Löschplan → freigabefähige Löschliste |
| 4 | `doc-redact` | Schwärzen | Nicht herauszugebende Daten entfernen | Auszug oder Dokumente → geschwärzte Kopie |
| 5 | `audit-trail` | Nachweis zusammenstellen | Wer, wann, was am Vorgang getan hat | Anfrage und Entscheidung → Prüfprotokoll |

**Ergebnis:** Auskunftskopie; Löschplan; Prüfprotokoll.

### `handwerk-scheduling` — Handwerk-Terminplanung / Планирование работ Handwerk

Plant Fertigungs- und Montageaufträge auf Termine, Material und Personal. Engpässe werden sichtbar, bevor der Kunde anruft; der Plan bleibt ein Vorschlag für die Leitung.

*Für wen:* handwerk, construction, manufacturing. *Zeitgewinn:* ca. 3 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `production-scheduling` | Termine planen | Aufträge auf Kapazität und Termine verteilen | offene Aufträge → Terminplan |
| 2 | `production-work-order` | Aufträge anlegen | Arbeitsfolgen und Prüfpunkte erzeugen | bestätigte Aufträge → Fertigungsaufträge |
| 3 | `production-material-plan` | Material sichern | Fehlmengen und Bestellvorschläge | Fertigungsaufträge → Materialliste mit Fehlmengen |
| 4 | `calendar-sync` | Kunden terminen | Montagetermine mit Kunden abstimmen | Terminplan → Kalendereinträge |
| 5 | `task-dispatch` | Trupps einteilen | Aufgaben und Zuständigkeiten verteilen | Terminplan und Personal → zugewiesene Aufträge |

**Ergebnis:** Terminplan; Fertigungsaufträge; Materialliste; Kundentermine.

### `inbox-triage` — Postfach-Triage / Разбор почты и распределение

Sortiert das gemeinsame Postfach nach Thema und Kanal, übernimmt Anhänge als Dokumente und verteilt sie an den richtigen Vorgang. Jede Nachricht bekommt einen nachvollziehbaren Bearbeitungsweg, statt in einem geteilten Postfach liegen zu bleiben.

*Für wen:* handwerk, services, logistics, realestate. *Zeitgewinn:* ca. 3 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `mail-sort` | Postfach sortieren | Thema und Kanal jeder Nachricht erkennen | E-Mail-Eingang → sortierte Nachrichten mit Vorschlag |
| 2 | `mail-accept` | Anhänge übernehmen | Anhänge als Dokumente anlegen und verknüpfen | Nachricht mit Anhang → Dokumente im Arbeitsbereich |
| 3 | `doc-detect` | Dokumenttyp erkennen | Typ und Fachkanal bestimmen | neue Dokumente → Dokumente mit Typ und Kanal |
| 4 | `doc-extract` | Daten extrahieren | Nummer, Datum, Betrag und Parteien auslesen | erkannte Dokumente → strukturierte Felder |
| 5 | `doc-assign` | Zuordnen und ablegen | Dokumente dem passenden Vorgang zuordnen | extrahierte Dokumente → Vorgänge mit vollständiger Ablage |

**Ergebnis:** Sortiertes Postfach; Dokumente mit extrahierten Feldern; Zugeordnete Vorgänge.

### `incident-response` — IT-Störung / ИТ-инцидент

Nimmt Störungsmeldungen an, erkennt bekannte Symptome und eskaliert nach Schweregrad. Die Dokumentation bleibt auch dann vollständig, wenn die Störung schnell behoben ist.

*Für wen:* it, agency, ecommerce, logistics, services. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `it-incident` | Störung aufnehmen | Schweregrad und betroffene Systeme bestimmen | Störungsmeldung → Incident mit Priorität |
| 2 | `monitor-systems` | Systemlage prüfen | Verfügbarkeit der Integrationen bewerten | Incident → Systemstatus |
| 3 | `support-escalation` | Eskalieren | Nächste Stufe und Benachrichtigung festlegen | Incident und Status → Eskalation |
| 4 | `task-dispatch` | Behebung zuweisen | Aufgabe mit Frist an das IT-Team | Eskalation → zugewiesene Aufgabe |
| 5 | `report-daily` | Nachbereiten | Störung und Auswirkung im Tagesbericht | Incident-Daten → Tagesbericht |

**Ergebnis:** Incident-Protokoll; Eskalationsliste; Tagesbericht.

### `invoice-approval` — Rechnungsfreigabe / Согласование счетов

Führt Eingangsrechnungen durch Prüfung und Freigabe bis zur Zahlungsvorbereitung. Jede Freigabe ist dokumentiert; Rechnungen mit geänderter Bankverbindung stoppen den Ablauf.

*Für wen:* services, handwerk, manufacturing, realestate, logistics. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `invoice-capture` | Rechnung erfassen | Beleg mit Duplikatschutz anlegen | Eingangsrechnung → erfasste Rechnung |
| 2 | `invoice-check` | Rechnung prüfen | Betrag, Lieferant und Bankverbindung | erfasste Rechnung → Prüfergebnis |
| 3 | `approval` | Freigabe einholen | Entscheidung mit Begründung dokumentieren | Prüfergebnis → Freigabe oder Ablehnung |
| 4 | `payment-match` | Zahlung vorbereiten | Offene Posten und Zahlweg prüfen | freigegebene Rechnung → Zahlungsvorschlag |
| 5 | `account-datev` | Buchung vorbereiten | Kontierung und Kontrollsumme | freigegebene Rechnung → Buchungsstapel |

**Ergebnis:** Freigabeprotokoll; Zahlungsvorschlag; Buchungsstapel.

### `kyc-sanctions` — KYC- und Sanktionsprüfung / KYC и проверка санкций

Prüft neue Geschäftspartner gegen Sanktions- und PEP-Listen und gleicht die KYC-Unterlagen ab. Treffer werden mit Ähnlichkeitsgrad vorgelegt, nicht automatisch abgelehnt. Die Entscheidung bleibt bei der verantwortlichen Person.

*Für wen:* banking, finance, legal, realestate, consulting. *Zeitgewinn:* ca. 1 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `legal-sanctions-kyc` | Listen abgleichen | Name und Land gegen Sanktionslisten prüfen | Geschäftspartner → Trefferliste mit Ähnlichkeitsgrad |
| 2 | `crm-contact` | Stammdaten prüfen | Kontakt und Identifikatoren vervollständigen | Trefferliste → vollständiger Kontakt |
| 3 | `compliance-check` | Unterlagen prüfen | KYC-Nachweise und Pflichtangaben kontrollieren | Kontakt → Lückenliste |
| 4 | `risk-flag` | Risiko markieren | Befund mit Schweregrad am Vorgang vermerken | Treffer und Lücken → Risiko-Hinweis |
| 5 | `approval` | Entscheidung einholen | Freigabe durch die verantwortliche Person | Risiko-Hinweis → dokumentierte Entscheidung |

**Ergebnis:** KYC-Prüfprotokoll; Risiko-Hinweis; Dokumentierte Entscheidung.

### `lead-to-cash` — Vom Lead zum Auftrag / От лида до заказа

Begleitet einen Lead durch Qualifizierung, Angebot und Nachfassen bis zum Auftrag. Jeder Schritt hat einen Besitzer und einen nächsten Termin, damit Chancen nicht an Stillstand sterben.

*Für wen:* services, agency, it, handwerk, consulting. *Zeitgewinn:* ca. 3 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `marketing-lead-nurture` | Lead qualifizieren | Phase und nächste Maßnahme bestimmen | neuer Kontakt → qualifizierter Lead |
| 2 | `crm-lead` | Lead anlegen | Kontakt mit Quelle und Notiz erfassen | Anfrage → Lead im CRM |
| 3 | `crm-deal` | Deal eröffnen | Wert und Phase festhalten | qualifizierter Lead → offener Deal |
| 4 | `sales-offer-draft` | Angebot erstellen | Positionen und Gültigkeit vorbereiten | Deal und Preisliste → Angebotsentwurf |
| 5 | `sales-followup-cadence` | Nachfassen planen | Kadenz mit Stopp-Regeln anlegen | Angebot → Follow-up-Aufgaben |

**Ergebnis:** Qualifizierter Lead; Angebotsentwurf; Follow-up-Plan.

### `monthly-kpi` — Monats-KPI-Bericht / Месячный KPI-отчёт

Sammelt die Kennzahlen des Monats aus allen Modulen, prüft sie auf Auffälligkeiten und leitet Empfehlungen ab. Der Bericht nennt zu jeder Zahl Zeitraum und Quelle, damit er im Management-Gespräch belastbar ist.

*Für wen:* services, handwerk, ecommerce, manufacturing, agency. *Zeitgewinn:* ca. 3 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `metric-collect` | Kennzahlen sammeln | Werte aus Dokumenten, Aufgaben und Finanzen | Monatsdaten → einheitliches Kennzahlenset |
| 2 | `report-daily` | Tageslage prüfen | Verlauf und offene Punkte des Monats | Kennzahlenset → Tagesübersicht |
| 3 | `knowledge-kpi-report` | Bericht erstellen | Management-Zusammenfassung mit Quellen | Kennzahlen → KPI-Bericht |
| 4 | `anomaly` | Auffälligkeiten finden | Abweichungen statistisch prüfen | Tagesreihen → Abweichungsbericht |
| 5 | `insight` | Empfehlungen ableiten | Regelbasierte Auswertung der Befunde | Bericht und Abweichungen → Maßnahmenempfehlungen |

**Ergebnis:** KPI-Bericht; Abweichungsbericht; Maßnahmenempfehlungen.

### `nps-loop` — Kundenstimmen und NPS / Голос клиента и NPS

Führt NPS-Umfragen durch, wertet Freitext aus und entwirft Antworten auf öffentliche Bewertungen. Themen mit häufiger Kritik fließen in den Redaktionsplan ein, statt nur in einer Statistik zu landen.

*Für wen:* services, ecommerce, gastronomy, handwerk, realestate. *Zeitgewinn:* ca. 1 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `support-nps` | NPS erheben | Umfrage auswerten und Themen gruppieren | Umfragezeitraum → NPS-Auswertung mit Themen |
| 2 | `support-review-response` | Bewertungen beantworten | Antwortentwürfe je Bewertung | öffentliche Bewertungen → Antwortentwürfe |
| 3 | `crm-activity` | Kundenhistorie ergänzen | Rückmeldung am Kontakt festhalten | NPS und Antworten → aktualisierte Historie |
| 4 | `insight` | Muster ableiten | Wiederkehrende Kritik und Ursachen | NPS-Auswertung → Empfehlungen |
| 5 | `marketing-content-brief` | Inhalte ableiten | Themen für Blog und FAQ briefen | Empfehlungen → Content-Briefings |

**Ergebnis:** NPS-Auswertung; Antwortentwürfe; Content-Briefings.

### `practice-recall` — Praxis-Recall / Напоминания в практике

Ermittelt aus Fristen und Einwilligungen, wer eine Kontroll- oder Impferinnerung erhalten soll, und bereitet Termine sowie Dokumentation vor. Der Agent stellt keine Diagnosen und versendet nichts ohne Freigabe.

*Für wen:* healthcare, therapy, care. *Zeitgewinn:* ca. 1 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `healthcare-recall` | Recall ermitteln | Fristen und Einwilligungen prüfen | Patientendaten → Recall-Liste |
| 2 | `healthcare-appointment` | Termine vergeben | Freie Slots vorschlagen | Recall-Liste → Terminvorschläge |
| 3 | `healthcare-documentation` | Dokumentation vorbereiten | Strukturierte Vorlage je Terminart | Terminvorschläge → Dokumentationsentwurf |
| 4 | `calendar-sync` | Termine eintragen | Praxis- und Patiententermin abstimmen | bestätigte Termine → Kalendereinträge |
| 5 | `task-dispatch` | Nachverfolgung | Offene Rückmeldungen zuweisen | Recall-Liste → Aufgaben |

**Ergebnis:** Recall-Liste; Terminvorschläge; Dokumentationsentwürfe.

### `recruiting-pipeline` — Recruiting-Pipeline / Рекрутинг

Prüft Bewerbungen gegen die Stellenanforderungen, bereitet Interviews vor und startet nach der Zusage das Onboarding. Menschen entscheiden; der Ablauf sorgt dafür, dass keine Bewerbung und kein Onboarding-Schritt verloren geht.

*Für wen:* handwerk, services, healthcare, it, gastronomy. *Zeitgewinn:* ca. 5 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `hr-recruiting-screening` | Bewerbungen prüfen | Muss- und Wunschkriterien abgleichen | Stellenprofil und Bewerbungen → Interview-Shortlist mit Begründung |
| 2 | `calendar-sync` | Interviews planen | Termine mit Kandidaten und Fachbereich abstimmen | Shortlist → Interviewtermine |
| 3 | `task-dispatch` | Entscheidung vorbereiten | Feedback und Entscheidung als Aufgabe | Interviewnotizen → Entscheidungsvorlage |
| 4 | `letter-draft` | Zusage oder Absage | Persönliches Schreiben aus Vorlage | Entscheidung → Schreiben als Entwurf |
| 5 | `hr-onboarding` | Onboarding starten | Aufgabenplan ab Startdatum anlegen | Zusage und Startdatum → Onboarding-Aufgaben |

**Ergebnis:** Shortlist; Interviewplan; Onboarding-Plan.

### `reservations` — Reservierungen / Бронирования

Nimmt Reservierungen an, prüft Verfügbarkeit und bereitet die Nachfassung bei No-Shows vor. Besondere Anlässe landen direkt am Gastprofil, damit der Service beim nächsten Besuch Bescheid weiß.

*Für wen:* gastronomy, hospitality, services. *Zeitgewinn:* ca. 1 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `hospitality-reservation` | Reservierung aufnehmen | Kapazität und Zeitfenster prüfen | Anfrage → bestätigte Reservierung oder Alternative |
| 2 | `hospitality-no-show` | No-Show nachfassen | Nicht wahrgenommene Termine bearbeiten | Reservierungsliste → Nachfass-Entwurf |
| 3 | `calendar-sync` | Kalender pflegen | Reservierungen im Teamkalender sichtbar | Reservierung → Kalendereintrag |
| 4 | `mail-reply` | Gast informieren | Bestätigung und Änderung formulieren | Reservierung → Antwortentwurf |
| 5 | `crm-contact` | Gastprofil pflegen | Anlässe und Vorlieben festhalten | Reservierung → aktualisiertes Profil |

**Ergebnis:** Reservierungsliste; Bestätigungen; Gastprofile.

### `returns-handling` — Retourenbearbeitung / Обработка возвратов

Nimmt Retourenanmeldungen an, prüft Fristen und Gründe und bereitet Erstattung oder Umtausch vor. Der Lagerstatus wird erst nach Wareneingang fortgeschrieben, damit die Buchhaltung nicht auf Annahmen sitzt.

*Für wen:* ecommerce, retail. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `ecommerce-order-sync` | Bestellung prüfen | Bestell- und Zahlungsdaten abgleichen | Retourenanmeldung → Bestellung mit Zahlungsstatus |
| 2 | `ecommerce-returns` | Retoure vorbereiten | Frist, Grund und Erstattungsweg prüfen | Bestellung → Retourenfall mit Vorschlag |
| 3 | `support-ticket` | Kundenkontakt anlegen | Anliegen als bearbeitbare Aufgabe | Retourenfall → Ticket für den Support |
| 4 | `ecommerce-payment-check` | Erstattung prüfen | Zahlweg und offene Beträge kontrollieren | Retourenfall → Erstattungsvorschlag |
| 5 | `mail-reply` | Antwort entwerfen | Kunden über Entscheidung und Ablauf informieren | Retourenfall → Antwortentwurf |

**Ergebnis:** Retourenentscheidung; Kundenantwort; Erstattungsvorschlag.

### `shift-planning` — Dienstplanung / Планирование смен

Erstellt den Dienstplan aus Öffnungszeiten und Verfügbarkeiten und gleicht Urlaube sowie Zeitkonten ab. Konflikte werden vor Veröffentlichung gemeldet, nicht danach.

*Für wen:* gastronomy, hospitality, healthcare, retail, logistics. *Zeitgewinn:* ca. 2 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `hospitality-shift-plan` | Dienstplan bauen | Schichten aus Bedarf und Verfügbarkeit | Woche und Bedarf → Dienstplan-Entwurf |
| 2 | `hr-leave-request` | Abwesenheiten prüfen | Urlaube und Sperrzeiten abgleichen | Dienstplan-Entwurf → Konfliktliste |
| 3 | `hr-time-tracking` | Zeitkonten prüfen | Grenzwerte und Pausen kontrollieren | erfasste Zeiten → Korrekturliste |
| 4 | `calendar-sync` | Plan veröffentlichen | Schichten im Teamkalender eintragen | freigegebener Plan → Kalendereinträge |
| 5 | `task-dispatch` | Vertretungen regeln | Offene Schichten als Aufgaben | Konfliktliste → Vertretungsaufgaben |

**Ergebnis:** Dienstplan; Konfliktliste; Vertretungsaufgaben.

### `supply-chain` — Lieferkette überwachen / Контроль цепочки поставок

Verfolgt offene Bestellungen und Liefertermine, bereitet die Inventur vor und bewertet Lieferanten nach Termintreue. Engpässe werden früh sichtbar, bevor die Produktion steht.

*Für wen:* manufacturing, logistics, handwerk, ecommerce, retail. *Zeitgewinn:* ca. 3 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `logistics-order-intake` | Bestellungen erfassen | Offene Bestellungen und Termine prüfen | Bestellungen → Auftragsliste |
| 2 | `logistics-delivery-tracking` | Liefertermine überwachen | Überfällige Lieferungen melden | offene Bestellungen → Verzugsliste |
| 3 | `logistics-inventory-count` | Inventur vorbereiten | Zähllisten und Differenzen planen | Lager und Stichtag → Zähllisten |
| 4 | `logistics-supplier-scorecard` | Lieferanten bewerten | Termintreue und Reklamationen | Bestell- und Reklamationsdaten → Lieferanten-Scorecard |
| 5 | `task-dispatch` | Maßnahmen verteilen | Engpässe als Aufgaben zuweisen | Verzugsliste und Scorecard → zugewiesene Maßnahmen |

**Ergebnis:** Verzugsliste; Inventurunterlagen; Lieferantenbewertung; Maßnahmenliste.

### `warehouse-count` — Lager und Inventur / Склад и инвентаризация

Bereitet die Inventur aus offenen Bestellungen und Lieferterminen vor und erzeugt Zähllisten je Lagerort. Differenzen werden erst nach der zweiten Zählung als Buchungsvorschlag ausgewiesen.

*Für wen:* manufacturing, logistics, ecommerce, retail, handwerk. *Zeitgewinn:* ca. 3 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `logistics-inventory-count` | Inventur planen | Zähllisten und Stichtag festlegen | Lager und Stichtag → Zähllisten |
| 2 | `logistics-delivery-tracking` | Wareneingang prüfen | Erwartete Lieferungen einbeziehen | offene Bestellungen → Lieferliste |
| 3 | `logistics-order-intake` | Bestellungen abgleichen | Offene Aufträge gegen Bestand prüfen | Aufträge → Abgleichsliste |
| 4 | `task-dispatch` | Zählung zuweisen | Verantwortliche und Fristen verteilen | Zähllisten → zugewiesene Inventuraufgaben |
| 5 | `knowledge-kpi-report` | Bestandsbericht | Kennzahlen zur Inventur berichten | Zählergebnisse → Inventurbericht |

**Ergebnis:** Zähllisten; Differenzprotokoll; Inventurbericht.

### `warranty-case` — Garantiefälle / Гарантийные случаи

Prüft Garantiefristen und Reparaturhistorie, bereitet die Entscheidung vor und hält die Kommunikation mit dem Kunden fest. Kulanz und Gewährleistung bleiben getrennt, damit die Entscheidung nachvollziehbar ist.

*Für wen:* handwerk, manufacturing, retail, ecommerce. *Zeitgewinn:* ca. 1 Std./Woche.

| # | Agent | Schritt | Zweck | Input → Output |
|---:|---|---|---|---|
| 1 | `production-warranty` | Garantie prüfen | Kaufdatum, Frist und Reparaturen abgleichen | Garantiemeldung → Entscheidungsvorlage |
| 2 | `crm-contact` | Kunden zuordnen | Kontakt und Historie vervollständigen | Garantiemeldung → Kontakt mit Historie |
| 3 | `task-dispatch` | Bearbeitung zuweisen | Zuständigkeit und Frist festlegen | Entscheidungsvorlage → zugewiesene Aufgabe |
| 4 | `letter-draft` | Kunden informieren | Entscheidung und nächster Schritt als Schreiben | Entscheidung → Schreiben als Entwurf |
| 5 | `doc-archive` | Nachweis ablegen | Fotos und Belege revisionssicher archivieren | Garantiefall → archivierter Vorgang |

**Ergebnis:** Garantieentscheidung; Kundenschreiben; Archivierter Nachweis.

## API und Витрина

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/v1/constellations` | Liste mit `steps`, `implementedSteps`, `conceptSteps`, `estimatedTimeSaved` |
| GET | `/api/v1/constellations/{id}` | Detail mit aufgelösten Agentennamen, `implemented` je Schritt und `unknownAgents` |
| POST | `/api/v1/constellations/{id}/instantiate` | Speichert die Vorlage als Workflow-Version (Schritte vom Typ `AGENT`) und gibt `workflowId` zurück |
| POST | `/api/v1/workflows/{definitionId}/run` | Startet den Prozess und führt die Kette bis Ende/Пауза/Fehler aus |
| POST | `/api/v1/workflow-runs/{runId}/resume` | `APPROVE` / `RETRY` / `SKIP` für einen pausierten Lauf |

Rechte: `constellations.view` (Lesen), `constellations.instantiate` (Instanziieren). `agents.view`/`agents.run` werden ebenfalls akzeptiert; die Migration `V84` vergibt die Rechte an OWNER, ADMIN, MANAGER und (nur Lesen) AUDITOR. Für den Lauf der gespeicherten Version gilt `workflows.run` (V88, OWNER/ADMIN/MANAGER).

**Antwort von `instantiate` (gekürzt):**

```json
{
  "constellationId": "inbox-triage",
  "workflowId": "0f0f…-…",
  "versionId": "1a2b…-…",
  "version": 1,
  "saved": true,
  "entryStep": "step-1",
  "stepCount": 6,
  "steps": [
    {
      "key": "step-1",
      "type": "AGENT",
      "agentId": "mail-sort",
      "next": "step-2"
    }
  ],
  "warnings": [
    "Die Schritte tragen keine festen Eingaben: Ein Lauf pausiert beim ersten Agenten, der Daten braucht, und wird mit RETRY samt Eingaben fortgesetzt."
  ]
}
```

Antwortet ein Schritt auf einen unbekannten Agenten oder ein Konzept, wird nichts gespeichert: `409 CONSTELLATION_NOT_EXECUTABLE` mit `steps` (`order`, `agentId`, `title`, `reason`).

### Was die Витрина lesen sollte

1. `implementedSteps` / `conceptSteps` aus der Liste als ehrliche Abdeckungsanzeige nutzen – keine Vorlage als „fertig“ darstellen, solange Schritte Konzepte sind.
2. In der Detailansicht je Schritt `implemented`, `agentName` und `unknownAgents` anzeigen; unbekannte Agenten sind ein Katalogfehler, kein UI-Fall.
3. `instantiate` speichert jetzt wirklich: `saved=true`, `workflowId` und `steps` anzeigen und den Lauf über `/workflows/{workflowId}/run` anbieten. Ein `409` nennt die nicht ausführbaren Schritte.
4. `estimatedTimeSaved.minutesPerWeek` für Sortierung/Filter, `label` für die Anzeige.
5. Ein gespeicherter Lauf endet oft mit `PAUSED_FOR_HUMAN_REVIEW` (die Vorlage kennt keine konkreten Eingaben); `GET /api/v1/workflow-runs/{id}` zeigt, welcher Schritt wartet, und `resume` mit `RETRY` + `inputs` setzt ihn fort.

## Tests

`ConstellationCatalogTest` erzwingt: 24–30 Vorlagen (aktuell 30), eindeutige IDs, lückenlose Schrittfolge, alle `agentId` im Katalog vorhanden, DE/RU-Namen und Ergebnisse gepflegt. `ConstellationCatalog.validate` prüft dieselben Regeln zur Laufzeit fail-soft: eine kaputte Datei wird geloggt und übersprungen, der Rest bleibt nutzbar.
