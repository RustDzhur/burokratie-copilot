# Agenten-Verifikation — Smoke-Lauf

Stand: 2026-10-27T12:48:06.174182Z · Datenbank `burokratie_test_smoke` · Tenant `9ad86a30-9820-45ba-b721-39d4f9b2386d`

Dieser Report wird von `AllAgentsSmokeIT` erzeugt: alle 126 Agenten werden mit demselben minimalen Kontext (`trigger=smoke`, keine Eingaben, leerer Tenant) aufgerufen. Ein Lauf ist
**OK**, wenn der Agent etwas getan hat; **REFUSED**, wenn er ohne Daten ehrlich ablehnt (kein Fehler); **ERROR**, wenn er eine Ausnahme geworfen hat.

## Ergebnis

| Katalog | Geprüft | Implementiert | OK | REFUSED | davon mit Reason-Code | ERROR | Dauer |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 126 | 126 | 126 | 5 | 121 | 13 | 0 | 525 ms |

**0 ERROR** — kein Agent hat eine unerwartete Ausnahme geworfen; alle 126 Agenten sind implementiert und wurden ausgeführt.

## Agenten

„Grund“ nennt den Reason-Code des Agenten und seine Summary. Nur 13 von 121 Refusals setzen `meta.reason`; bei den übrigen ist die deutsche Summary die Begründung. „Für echte Arbeit nötig“ ist aus beidem abgeleitet.

| Agent | Kategorie | Status | Grund | Für echte Arbeit nötig |
|---|---|---|---|---|
| `account-datev` | finance | REFUSED | DATEV: keine geprüften, noch nicht exportierten eingehenden Rechnungen (APPROVED/PARTIALLY_PAID/PAID ohne Buchungsblockade) gefunden. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `anomaly` | analysis | REFUSED | Zu wenige Datenpunkte für eine Anomalie-Erkennung: keine Reihe hat mindestens 7 Tage mit Daten im Fenster. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `api-bridge` | integration | REFUSED | Keine entityType angegeben; ohne sie ist keine Route bestimmbar. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `approval` | processes | REFUSED | subjectType und subjectId sind erforderlich, um eine Freigabe anzufragen | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `audit-trail` | compliance | REFUSED | entityType fehlt — das Prüfprotokoll braucht einen Objekttyp | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `banking-fee-audit` | banking | REFUSED | Im Zeitraum 2025-10-27 bis 2026-10-27 wurden keine Gebührenbuchungen gefunden. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `banking-payment-order` | banking | REFUSED | Keine invoiceIds angegeben; es gibt nichts für den Zahlungsauftrag vorzubereiten. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `banking-reconcile` | banking | REFUSED | Bankabgleich: 'periodFrom' und 'periodTo' sind als gültiger Zeitraum erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `banking-statement-import` | banking | REFUSED | Kontoauszug-Import: 'accountId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `budget-watch` | finance | REFUSED | Budget-Überwachung: keine Budgets konfiguriert (Einstellungen finance.budget.<kategorie>.monthly_eur). | Feature/Konfiguration aktivieren (OCR, KI, Integration, Zugangsdaten). |
| `calendar-sync` | processes | REFUSED | Keine CalDAV-Verbindung für diesen Nutzer — der Abgleich kann nur intern erfolgen | Quellsystem verbinden/konfigurieren (Postfach, Kalender, Integration). |
| `case-file` | processes | REFUSED | caseId fehlt — ohne Vorgang gibt es keine Akte | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `cashflow` | finance | REFUSED | Liquiditätsplanung: keine offenen Rechnungen im Zeitraum 2026-10-27 bis 2026-11-26 — keine Prognose möglich. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `chat-assist` | communication | REFUSED | Keine Anfrage vorhanden: das Feld 'text' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `compliance-check` | compliance | OK | Compliance-Prüfung für workspace: 5 Anforderungen verletzt, 2 erfüllt | Nichts — der Agent hat auf dem leeren Tenant bereits gearbeitet. |
| `crm-activity` | crm | REFUSED | Aktivitäten-Protokoll: ein Betreff ('subject') ist erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `crm-contact` | crm | REFUSED | Kontaktpflege: kein Kontakt gefunden — weder contactId noch eine bekannte E-Mail. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `crm-deal` | crm | REFUSED | Deal-Pipeline: keine Deals im Tenant gefunden. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `crm-followup` | crm | REFUSED | Follow-up: 'caseId' oder 'contactId' ist erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `crm-lead` | crm | REFUSED | Lead-Erfassung: ein Name ist erforderlich (Input 'name'). | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `data-sync` | integration | REFUSED | Es gibt keine verbundenen Systeme, deren Datenstände verglichen werden könnten. | Quellsystem verbinden/konfigurieren (Postfach, Kalender, Integration). |
| `deadline-watch` | processes | REFUSED | Keine überfälligen oder nahenden Fristen — es gibt nichts zu eskalieren | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `doc-archive` | documents | REFUSED | Keine documentId angegeben; es gibt nichts zu archivieren. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-assign` | documents | REFUSED | Keine documentId angegeben; es gibt nichts zuzuordnen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-datev` | documents | REFUSED | Keine documentIds angegeben; es gibt kein Paket zu bauen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-detect` | documents | REFUSED | Dokumenttyp nicht erkannt: keine Regeln passen zu Dateiname, MIME-Typ oder Texthinweis. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `doc-extract` | documents | REFUSED | Keine documentId angegeben; ohne Dokument gibt es nichts zu extrahieren. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-ocr` | documents | REFUSED | Keine documentId angegeben; es gibt keinen Text zu erkennen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-redact` | documents | REFUSED | Keine documentId angegeben; es gibt keinen Text zu prüfen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-scan` | documents | REFUSED | Weder deviceId noch folderPath angegeben; es gibt keinen Scan-Auftrag anzunehmen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-sign` | documents | REFUSED | Keine documentIds angegeben; es gibt keine Dokumente zur Unterschrift vorzubereiten. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `doc-validate` | documents | REFUSED | Keine documentId angegeben; es gibt nichts zu prüfen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `dunning` | finance | REFUSED | Mahnwesen: keine überfälligen Ausgangsrechnungen — nichts vorzubereiten. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `ecommerce-catalog-sync` | ecommerce | REFUSED | Keine Artikel-/Katalogdaten gefunden: es gibt keine Product-/Article-Tabellen und keine Rechnungspositionen mit Artikeldaten. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `ecommerce-marketplace-feed` | ecommerce | REFUSED | marketplace fehlt; ohne Zielmarktplatz gibt es keinen Feed zu bauen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `ecommerce-order-sync` | ecommerce | REFUSED | Bestell-Synchronisation: keine Bestelldaten übergeben — ohne Shop-/Marktplatz-Anbindung oder 'orders'-JSON gibt es nichts zu importieren. | Quellsystem verbinden/konfigurieren (Postfach, Kalender, Integration). |
| `ecommerce-payment-check` | ecommerce | REFUSED | Zahlungsprüfung: keine überfälligen offenen Ausgangsrechnungen im geprüften Datenbestand. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `ecommerce-price-monitor` | ecommerce | REFUSED | marginFloorPercent fehlt; ohne Marge lässt sich keine Preisabweichung bewerten. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `ecommerce-returns` | ecommerce | REFUSED | Retourenbearbeitung: 'orderId' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `expense-sort` | finance | REFUSED | Beleg-Sortierung: keine eingehenden Rechnungen gefunden. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `forecast` | analysis | REFUSED | Zu wenige Datenpunkte für eine Prognose: keine Reihe hat mindestens 5 Tage mit Daten im Fenster. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `gdpr-guard` | compliance | REFUSED | Keine Eingabe: contactId, documentId oder query angeben, damit etwas zu prüfen ist | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `healthcare-appointment` | healthcare | REFUSED | Terminvergabe: 'appointmentType' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `healthcare-billing` | healthcare | REFUSED | NO_INPUT — Für die Abrechnung wird eine gültige patientContactId benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `healthcare-documentation` | healthcare | REFUSED | Dokumentation: 'patientContactId', 'encounterDate' und 'documentType' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `healthcare-recall` | healthcare | REFUSED | Recall: 'recallType' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `healthcare-shift-handover` | healthcare | REFUSED | NO_INPUT — Für die Schichtübergabe wird eine wardId benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hospitality-event-plan` | hospitality | REFUSED | NO_INPUT — Für die Veranstaltung wird ein Name benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hospitality-menu-cost` | hospitality | REFUSED | NO_INPUT — Für die Speisekalkulation werden menuItems benötigt (JSON-Array). | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hospitality-no-show` | hospitality | REFUSED | No-Show: 'reservationId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hospitality-reservation` | hospitality | REFUSED | Reservierung: 'guestName', 'dateTime' und 'partySize' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hospitality-shift-plan` | hospitality | REFUSED | Dienstplan: 'week' fehlt oder ist kein gültiges Format YYYY-Www. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hr-leave-request` | hr | REFUSED | Urlaubsantrag: 'employeeId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hr-onboarding` | hr | REFUSED | Für den Onboarding-Plan werden Name und Startdatum benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hr-payroll-data` | hr | REFUSED | month im Format YYYY-MM fehlt oder ist ungültig; ohne Monat keine Lohndaten. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hr-recruiting-screening` | hr | REFUSED | Bewerber-Screening: 'jobId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hr-time-tracking` | hr | REFUSED | Zeiterfassungs-Prüfung: 'periodFrom' und 'periodTo' sind als gültiger Zeitraum erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `hr-training` | hr | REFUSED | Schulungsplanung: 'requiredTrainings' fehlt — ohne Pflichtschulungen gibt es keinen Bedarf zu planen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `insight` | analysis | REFUSED | Keine Daten für eine Auswertung: keine Dokumente, Aufgaben, Rechnungen, Konten oder verbundenen Systeme. | Quellsystem verbinden/konfigurieren (Postfach, Kalender, Integration). |
| `invoice-capture` | finance | REFUSED | Rechnungserfassung: kein Text übergeben (Eingabe rawText fehlt) — es gibt nichts zu erfassen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `invoice-check` | finance | REFUSED | Rechnungsprüfung: keine Rechnung gefunden (keine offenen eingehenden Rechnungen oder unbekannte invoiceId). | Existierendes Objekt referenzieren; die ID gehört nicht zum Tenant oder existiert nicht. |
| `it-access-review` | it | OK | Zugriffsprüfung: 1 Mitgliedschaft(en) geprüft, 1 Auffälligkeit(en), davon 0 mit hoher Priorität. | Nichts — der Agent hat auf dem leeren Tenant bereits gearbeitet. |
| `it-asset-inventory` | it | REFUSED | Keine IT-Assets gefunden: keine Geräte und keine SAAS-/Abo-Verträge. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `it-backup-check` | it | REFUSED | Backup-Prüfung: 'periodFrom' und 'periodTo' sind als gültiger Zeitraum erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `it-incident` | it | REFUSED | Störungsmeldung: 'title' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `it-patch-window` | it | REFUSED | Patch-Fenster: 'patchIds' fehlt — ohne Patches gibt es kein Fenster zu planen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `knowledge-base` | knowledge | REFUSED | NO_DATA — Keine Wissensartikel im gewählten Ausschnitt gefunden. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `knowledge-kpi-report` | knowledge | OK | KPI-Bericht für Zeitraum: 2026-09-01 bis 2026-09-30 mit 10 Kennzahlen (7 exakt, 3 ungefähr). | Nichts — der Agent hat auf dem leeren Tenant bereits gearbeitet. |
| `knowledge-meeting-memory` | knowledge | REFUSED | NO_INPUT — Ohne Besprechungsnotizen kann kein Gedächtnis festgehalten werden. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `knowledge-policy-digest` | knowledge | REFUSED | NO_INPUT — Es wurden keine Richtlinien angegeben (policyIds). | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `knowledge-search-answer` | knowledge | REFUSED | NO_INPUT — Ohne Frage kann die Wissenssuche nichts belegen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `legal-audit-prep` | legal | REFUSED | NO_INPUT — Für die Audit-Vorbereitung werden periodFrom und periodTo benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `legal-contract-control` | legal | REFUSED | Im Zeitfenster von 90 Tagen enden keine Verträge und es sind keine offenen Vertragspflichten fällig. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `legal-gdpr-request` | legal | REFUSED | Ohne Kontaktbezug lässt sich keine DSGVO-Anfrage bearbeiten. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `legal-litigation-file` | legal | REFUSED | NO_INPUT — Für die Streitfall-Akte wird eine gültige caseId benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `legal-nda-check` | legal | REFUSED | NDA-Prüfung: 'documentId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `legal-sanctions-kyc` | legal | REFUSED | Sanktions-/KYC-Prüfung: 'contactId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `letter-draft` | communication | REFUSED | Keine Vorlage benannt: 'templateCode' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `logistics-customs` | logistics | REFUSED | Zollanmeldung: 'shipmentId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `logistics-delivery-tracking` | logistics | REFUSED | Im Zeitfenster bis 2026-11-10 ist keine offene Bestellung überfällig oder kurzfristig fällig. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `logistics-inventory-count` | logistics | REFUSED | Inventur: 'warehouseId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `logistics-order-intake` | logistics | REFUSED | Auftragserfassung: weder 'orderText' noch 'items' übergeben. | Eingaben oder Daten gemäß Manifest bereitstellen; die Begründung steht in der Summary. |
| `logistics-route-planning` | logistics | REFUSED | deliveryDate im Format YYYY-MM-DD fehlt; ohne Liefertag keine Tourenplanung. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `logistics-supplier-scorecard` | logistics | REFUSED | Lieferantenbewertung: 'periodFrom' und 'periodTo' sind als gültiger Zeitraum erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `mail-accept` | communication | REFUSED | mailId fehlt oder ist keine UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `mail-reply` | communication | REFUSED | mailId fehlt oder ist keine UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `mail-sort` | communication | REFUSED | Keine Maildaten vorhanden: Betreff, Text und Absender fehlen. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `marketing-content-brief` | marketing | REFUSED | Content-Briefing: 'topic' und 'targetAudience' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `marketing-lead-nurture` | marketing | REFUSED | Lead-Nurturing: 'contactId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `marketing-newsletter` | marketing | REFUSED | Newsletter: 'campaignName' und 'audienceSegment' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `marketing-sea-campaign` | marketing | REFUSED | periodFrom und periodTo im Format YYYY-MM-DD sind erforderlich; ohne Zeitraum keine Kampagnenplanung. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `marketing-seo-audit` | marketing | REFUSED | SEO-Audit: 'siteUrl' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `marketing-social-calendar` | marketing | REFUSED | Social-Redaktionsplan: 'startDate' (YYYY-MM-DD) fehlt oder ist ungültig. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `meeting-notes` | communication | REFUSED | Kein Titel vorhanden: 'title' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `metric-collect` | analysis | REFUSED | Keine Daten gesammelt: keine verbundenen Systeme und keine Datensätze in Dokumenten, Aufgaben, Rechnungen oder Banking. | Quellsystem verbinden/konfigurieren (Postfach, Kalender, Integration). |
| `monitor-systems` | integration | REFUSED | Es gibt keine verbundenen Systeme, die überwacht werden könnten. | Quellsystem verbinden/konfigurieren (Postfach, Kalender, Integration). |
| `payment-match` | finance | REFUSED | Zahlungsabgleich: keine unzugeordneten Bankeingänge vorhanden. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `phone-transcript` | communication | REFUSED | Kein Gesprächstext vorhanden: 'text' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `production-material-plan` | production | REFUSED | Materialplanung: keine Stücklisten oder Materialangaben gefunden ('billOfMaterials' oder Fertigungsaufträge mit Material). | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `production-quality-check` | production | REFUSED | workOrderId fehlt; ohne Auftrag keine Qualitätsprüfung. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `production-quote-calc` | production | REFUSED | productId (UUID) fehlt oder ist ungültig; ohne Produkt keine Kalkulation. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `production-scheduling` | production | REFUSED | Terminplanung: keine offenen Fertigungsaufträge gefunden. Zuerst 'production-work-order' ausführen oder 'workOrders' übergeben. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `production-warranty` | production | REFUSED | Garantiefall: 'orderId', 'purchaseDate' und 'defectDescription' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `production-work-order` | production | REFUSED | Fertigungsauftrag: 'orderId', 'productId', 'quantity' und 'dueDate' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `realestate-listing` | realestate | REFUSED | MISSING_REQUIRED — Für das Exposé fehlen Pflichtangaben: propertyId, livingArea, rooms, price. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `realestate-maintenance` | realestate | REFUSED | Objektwartung: 'propertyId' und 'issueDescription' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `realestate-rent-dunning` | realestate | REFUSED | NO_INPUT — Für die Mietrückstände werden periodFrom und periodTo benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `realestate-tenant-admin` | realestate | REFUSED | Mieterverwaltung: 'propertyId', 'tenantContactId' und 'effectiveDate' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `realestate-viewing` | realestate | REFUSED | NO_INPUT — Für die Besichtigung wird eine propertyId benötigt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `reminder` | communication | REFUSED | Kein Text vorhanden: das Feld 'text' fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `report-daily` | analysis | REFUSED | Keine Aktivität am 2026-10-27: keine neuen Dokumente oder Aufgaben, keine Fristen und keine Zahlungen. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `retention` | compliance | OK | Aufbewahrungsprüfung: keine Löschkandidaten | Nichts — der Agent hat auf dem leeren Tenant bereits gearbeitet. |
| `risk-flag` | compliance | OK | Risiko-Prüfung abgeschlossen: keine neuen Befunde | Nichts — der Agent hat auf dem leeren Tenant bereits gearbeitet. |
| `sales-followup-cadence` | sales | REFUSED | Follow-up-Kadenz: 'dealId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `sales-offer-draft` | sales | REFUSED | Angebotserstellung: 'contactId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `sales-pipeline-review` | sales | REFUSED | Keine offenen Deals im Filter gefunden; es gibt keine Pipeline zu prüfen. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `sales-price-book` | sales | REFUSED | effectiveFrom im Format YYYY-MM-DD fehlt; ohne Gültigkeitsdatum gibt es kein Preisbuch. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `sales-win-loss` | sales | REFUSED | periodFrom und periodTo im Format YYYY-MM-DD sind erforderlich; ohne Zeitraum keine Auswertung. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `support-escalation` | support | REFUSED | Eskalation: 'ticketId' fehlt oder ist keine gültige UUID. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `support-faq-suggest` | support | REFUSED | periodFrom und periodTo im Format YYYY-MM-DD sind erforderlich; ohne Zeitraum keine FAQ-Auswertung. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `support-nps` | support | REFUSED | NPS: 'surveyPeriod' (YYYY-MM) fehlt. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `support-review-response` | support | REFUSED | Bewertungsantwort: 'reviewText', 'rating' und 'platform' sind erforderlich. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `support-sla-report` | support | REFUSED | periodFrom und periodTo im Format YYYY-MM-DD sind erforderlich; ohne Zeitraum kein SLA-Bericht. | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `support-ticket` | support | REFUSED | Ohne Betreff lässt sich kein Ticket anlegen. | Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| `task-dispatch` | processes | REFUSED | title fehlt — eine Aufgabe ohne Titel kann niemand annehmen | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |
| `workflow-route` | processes | REFUSED | Weder caseId noch documentId angegeben — es fehlt der Vorgang, der gesteuert werden soll | Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, Text). |

## Gründe für REFUSED

Die Klasse kommt aus `meta.reason`, wenn der Agent einen Code setzt; sonst ist sie aus der Summary abgeleitet. Maschinenlesbare Codes: MISSING_REQUIRED=1, NO_DATA=1, NO_INPUT=11; ohne Code: 108.

| Grundklasse | Anzahl | Bedeutung |
|---|---:|---|
| NO_INPUT | 88 | Pflicht-Eingaben fehlen (Objekt-ID, Zeitraum, Text …). |
| NO_DATA | 24 | Der Tenant hat keine passenden Daten (Dokumente, Rechnungen, Aufgaben, Verträge …). |
| NO_SOURCE | 6 | Quellsystem/Integration ist nicht verbunden oder konfiguriert. |
| NOT_CONFIGURED | 1 | Feature oder Einstellung ist nicht aktiviert/konfiguriert. |
| NOT_FOUND | 1 | Das referenzierte Objekt existiert nicht oder gehört einem anderen Tenant. |
| UNSPECIFIED | 1 | Kein Reason-Code und keine eindeutige Summary-Klasse; Summary lesen. |

## Fehler

Keine. Alle Agenten haben mit einem `AgentResult` geantwortet.

## Reproduktion

```bash
cd services/api
BC_TEST_DB_NAME=burokratie_test_smoke ./mvnw -q verify -Dit.test=AllAgentsSmokeIT
```
