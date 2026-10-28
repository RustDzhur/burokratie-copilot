# Agentenkatalog — vollständige Abdeckung für Unternehmen in Deutschland

Der Katalog umfasst **126 Agenten in 22 Kategorien** — alle 126 mit Java-Implementierung, kein Konzept mehr. Grundlage sind die Manifeste unter `services/api/src/main/resources/agents/<category>/<id>.json`; die Laufzeit-API und das Konzept „implementiert = Manifest + Bean“ stehen in [RUNTIME.md](RUNTIME.md).

Hinweis zur Schema-Erweiterung: `category` ist eine Zeichenkette im Manifest und wird in
`AgentRegistry.CATEGORIES` validiert — die Kategorien sind **kein** DB-CHECK, daher war für die
neuen Branchen keine Kategorie-Migration nötig. Die zusätzlichen Felder `systems` (Kanäle),
`version` und `status` liegen im freien `config`-Objekt des Manifests; so bleibt die bestehende
Manifest-Schema-Version kompatibel. `V84` fügt nur die Rechte für die Workflow-Vorlagen hinzu.

Stand: Die 30 Workflow-Vorlagen (Konstellationen) sind vollständig umgesetzt — jeder Schritt hat eine Java-Bean. Alle 126 Agenten sind implementiert (Manifest + Bean), es bleibt kein Konzept. Kein Agent verspricht eine Funktion, die nicht als Manifest beschrieben ist; die Spalte **Status** sagt, ob zusätzlich eine Bean existiert.

Laufzeit-Verifikation: `AllAgentsSmokeIT` hat **alle 126 Agenten** einmal mit leerem Tenant ausgeführt — **126 von 126 geprüft, 5 OK, 121 REFUSED, 0 ERROR**. Die vollständige Tabelle mit Gründen und dem, was für echte Arbeit fehlt, steht in [AGENT_VERIFICATION.md](AGENT_VERIFICATION.md).

## Abdeckung nach Kategorie

| Kategorie | Schlüssel | Agenten | Implementiert | Konzepte |
|---|---|---:|---:|---:|
| Dokumente & Ablage | `documents` | 10 | 10 | 0 |
| Kommunikation | `communication` | 8 | 8 | 0 |
| Finanzen | `finance` | 8 | 8 | 0 |
| Prozesse | `processes` | 6 | 6 | 0 |
| Compliance | `compliance` | 5 | 5 | 0 |
| Analyse | `analysis` | 5 | 5 | 0 |
| CRM | `crm` | 5 | 5 | 0 |
| Integration | `integration` | 3 | 3 | 0 |
| Marketing | `marketing` | 6 | 6 | 0 |
| Vertrieb | `sales` | 5 | 5 | 0 |
| Personal | `hr` | 6 | 6 | 0 |
| Bank & Zahlungsverkehr | `banking` | 4 | 4 | 0 |
| Recht & Datenschutz | `legal` | 6 | 6 | 0 |
| Logistik | `logistics` | 6 | 6 | 0 |
| Kundensupport | `support` | 6 | 6 | 0 |
| E-Commerce | `ecommerce` | 6 | 6 | 0 |
| Produktion & Handwerk | `production` | 6 | 6 | 0 |
| Immobilien | `realestate` | 5 | 5 | 0 |
| Gastronomie & Hotellerie | `hospitality` | 5 | 5 | 0 |
| Gesundheit & Pflege | `healthcare` | 5 | 5 | 0 |
| IT & Betrieb | `it` | 5 | 5 | 0 |
| Wissen & Reporting | `knowledge` | 5 | 5 | 0 |
| **Gesamt** | | **126** | **126** | **0** |

## Branchenabdeckung (geschlossene Lücken)

| Branche / Aufgabe | Agenten (Auswahl) |
|---|---|
| Handwerk & Bau | `production-work-order`, `production-quote-calc`, `production-scheduling`, `production-warranty`, `realestate-maintenance` |
| Produktion & Fertigung | `production-material-plan`, `production-quality-check`, `production-warranty` |
| Handel & E-Commerce | `ecommerce-catalog-sync`, `ecommerce-price-monitor`, `ecommerce-order-sync`, `ecommerce-payment-check`, `ecommerce-marketplace-feed`, `ecommerce-returns` |
| Logistik & Transport | `logistics-order-intake`, `logistics-delivery-tracking`, `logistics-inventory-count`, `logistics-customs`, `logistics-route-planning`, `logistics-supplier-scorecard` |
| Gastronomie & Hotellerie | `hospitality-reservation`, `hospitality-shift-plan`, `hospitality-menu-cost`, `hospitality-no-show`, `hospitality-event-plan` |
| Immobilien | `realestate-listing`, `realestate-tenant-admin`, `realestate-rent-dunning`, `realestate-viewing`, `realestate-maintenance` |
| Gesundheit & Pflege | `healthcare-appointment`, `healthcare-documentation`, `healthcare-billing`, `healthcare-recall`, `healthcare-shift-handover` |
| IT & Betrieb | `it-incident`, `it-access-review`, `it-backup-check`, `it-patch-window`, `it-asset-inventory` |
| Marketing & Agenturen | `marketing-seo-audit`, `marketing-sea-campaign`, `marketing-social-calendar`, `marketing-content-brief`, `marketing-newsletter`, `marketing-lead-nurture` |
| Vertrieb & CRM | `sales-pipeline-review`, `sales-offer-draft`, `sales-price-book`, `sales-followup-cadence`, `sales-win-loss` |
| Personal & Recruiting | `hr-recruiting-screening`, `hr-onboarding`, `hr-time-tracking`, `hr-payroll-data`, `hr-leave-request`, `hr-training` |
| Bank & Zahlungsverkehr | `banking-statement-import`, `banking-reconcile`, `banking-payment-order`, `banking-fee-audit` |
| Recht & Datenschutz | `legal-gdpr-request`, `legal-nda-check`, `legal-contract-control`, `legal-sanctions-kyc`, `legal-audit-prep`, `legal-litigation-file` |
| Kundensupport | `support-ticket`, `support-faq-suggest`, `support-nps`, `support-review-response`, `support-escalation`, `support-sla-report` |
| Wissen & Reporting | `knowledge-kpi-report`, `knowledge-base`, `knowledge-policy-digest`, `knowledge-meeting-memory`, `knowledge-search-answer` |
| Vereine & NKO | `compliance-check`, `budget-watch`, `legal-gdpr-request`, `report-daily` (über Prozess-Vorlagen kombinierbar) |

## Alle Agenten

| ID | Kategorie | Name | Was er macht | Status |
|---|---|---|---|---|
| `doc-archive` | Dokumente & Ablage | Dokument-Archivierung | Bestätigt die Archivreife eines Dokuments (alle Prüfungen bestanden) und setzt das Archivierungsdatum. | implementiert |
| `doc-assign` | Dokumente & Ablage | Zuordnung & Ablage | Ordnet ein Dokument per Regeln einem Vorgang oder Kontakt zu und legt die Verbindung fest. | implementiert |
| `doc-datev` | Dokumente & Ablage | DATEV-Aufbereitung | Stellt ein Paket für den DATEV-Import zusammen: Feldzuordnung, Kontrollsumme und Warnungen. | implementiert |
| `doc-detect` | Dokumente & Ablage | Dokument-Erkennung | Erkennt Dokumenttypen und steuert die weitere Verarbeitung an. | implementiert |
| `doc-extract` | Dokumente & Ablage | Daten-Extraktion | Extrahiert Nummer, Daten, Beträge und Parteien aus Dokumenten nach festen Regeln, ohne externe KI. | implementiert |
| `doc-ocr` | Dokumente & Ablage | OCR-Texterkennung | Bereitet den Text gescannter Seiten auf; wenn OCR deaktiviert ist (BC_ALLOW_NO_OCR), wird ehrlich abgelehnt. | implementiert |
| `doc-redact` | Dokumente & Ablage | Anonymisierung | Findet personenbezogene Daten per Muster (IBAN, E-Mail, Telefon, Adresse) und liefert einen Schwärzungs-Plan; schneidet nichts ohne Bestätigung heraus. | implementiert |
| `doc-scan` | Dokumente & Ablage | Scan-Eingang | Nimmt Scan-Aufträge an: ordnet Geräte oder Ordner zu (devices/ingest) und erzeugt Verarbeitungsaufträge. | implementiert |
| `doc-sign` | Dokumente & Ablage | Signatur-Prozess | Bereitet Dokumente zur Unterschrift vor: legt je Dokument eine Signatur-Aufgabe an, ohne externe Anbieter. | implementiert |
| `doc-validate` | Dokumente & Ablage | Dokument-Validierung | Prüft Pflichtfelder, Plausibilität (Zukunfts-Daten, negative Beträge) und Vollständigkeit; liefert die Liste der Probleme. | implementiert |
| `chat-assist` | Kommunikation | Chat-Assistenz | Liefert zu typischen Kundenanfragen regelbasierte Antwort-Bausteine (ohne externe KI, ohne Netz). | implementiert |
| `letter-draft` | Kommunikation | Briefentwurf | Erzeugt Standard-Schreiben aus einer veröffentlichten Vorlage mit den Daten von Kontakt/Vorgang und legt das erzeugte Dokument ab. | implementiert |
| `mail-accept` | Kommunikation | E-Mail-Annahme | Übernimmt Anhänge eingehender E-Mails in den Dokumentenumlauf (documents) und verknüpft sie mit dem Vorgang. | implementiert |
| `mail-reply` | Kommunikation | Antwort-Assistenz | Bereitet Antwort-Entwürfe auf eingehende E-Mails vor — aus Vorlagen (templates) oder als regelbasierte Eingangsbestätigung. | implementiert |
| `mail-sort` | Kommunikation | E-Mail-Sortierung | Klassifiziert eingehende E-Mails nach Regeln (Betreff, Absender, Anhänge) und schlägt Kanal und Vorgang vor. | implementiert |
| `meeting-notes` | Kommunikation | Besprechungsnotizen | Formt eine Besprechungsnotiz: Teilnehmer, Entscheidungen und Aufgaben. | implementiert |
| `phone-transcript` | Kommunikation | Anruf-Transkription | Legt aus diktiertem oder getipptem Text eine Gesprächsnotiz an und hängt sie an Vorgang und Kontakt. | implementiert |
| `reminder` | Kommunikation | Erinnerungen | Findet Fristen und Termine in Texten und Aufgaben und schlägt Erinnerungen vor (Aufgaben und Fristen über das deadlines-Modul). | implementiert |
| `account-datev` | Finanzen | Buchhaltung & DATEV | Bereitet geprüfte eingehende Rechnungen als DATEV-Buchungssätze vor: Kontierung nach Kostenkategorie-Regeln, Kontrollsummen Soll/Haben und Export-Markierung im Rechnungsmodul. | implementiert |
| `budget-watch` | Finanzen | Budget-Überwachung | Vergleicht die laufenden Ausgaben je Kostenkategorie (aus Rechnungspositionen) mit den Monatsbudgets aus den Einstellungen und meldet Abweichungen als Bericht. | implementiert |
| `cashflow` | Finanzen | Liquiditätsplanung | Stellt erwartete Zuflüsse (offene Ausgangsrechnungen) und Abflüsse (offene Eingangsrechnungen) zusammen mit dem letzten Bankbestand zu einer rollierenden Liquiditätsprognose. | implementiert |
| `dunning` | Finanzen | Mahnwesen | Findet überfällige Ausgangsrechnungen und bereitet Mahnschreiben als E-Mail-Entwürfe vor. | implementiert |
| `expense-sort` | Finanzen | Beleg-Sortierung | Verteilt Ausgabenbelege (Positionen eingehender Rechnungen) nach Stichwort-Regeln auf Kostenkategorien, die Buchhaltung, DATEV-Kontierung und Budget-Überwachung weiterverwenden. | implementiert |
| `invoice-capture` | Finanzen | Rechnungserfassung | Liest die Buchhaltungsfelder aus eingehenden Rechnungen (Nummer, Daten, Netto-, Steuer- und Bruttobetrag, Gegenpartei) nach festen Regeln aus dem Rechnungstext und legt die Rechnung im Rechnungsmodul an. | implementiert |
| `invoice-check` | Finanzen | Rechnungsprüfung | Prüft eingehende Rechnungen gegen die Regeln des Rechnungsmoduls: Dublette über Nummer und Lieferant, Betragsgrenzen aus den Einstellungen, Vorhandensein der Gegenpartei, und berichtet alle Abweichungen. | implementiert |
| `payment-match` | Finanzen | Zahlungsabgleich | Sucht für unzugeordnete Bankeingänge passende offene Ausgangsrechnungen (Rechnungsnummer im Verwendungszweck oder exakt offener Betrag) und markiert Kandidaten zur Bestätigung durch eine Person. | implementiert |
| `approval` | Prozesse | Freigaben | Legt Freigabekreise an und führt sie: wer entscheidet, was entschieden wurde und wie die Entscheidung dokumentiert ist. | implementiert |
| `calendar-sync` | Prozesse | Kalender-Sync | Vergleicht Fristen und Termine des internen Kalenders mit dem externen Kalender (CalDAV) und meldet Abweichungen, ohne etwas zu verändern. | implementiert |
| `case-file` | Prozesse | Vorgangsakte | Stellt die Akte eines Vorgangs zusammen: Dokumente, Schritte, offene Aufgaben — ein einziger Überblick. | implementiert |
| `deadline-watch` | Prozesse | Fristen-Überwachung | Beobachtet die Fristen aller Vorgänge, eskaliert überfällige und meldet nahende Fristen. | implementiert |
| `task-dispatch` | Prozesse | Aufgaben-Verteilung | Erzeugt Aufgaben aus Ereignissen und weist sie nach den Rollenregeln des Mandanten zu. | implementiert |
| `workflow-route` | Prozesse | Workflow-Steuerung | Ermittelt aus dem Zustand von Vorgang und Dokumenten den nächsten Prozessschritt und an wen der Vorgang übergeben wird. | implementiert |
| `audit-trail` | Compliance | Prüfprotokoll | Sammelt den vollständigen Prüfpfad zu einem Objekt — wer hat wann was getan — und bereitet ihn als strukturierte Ausgabe (JSON) im Laufprotokoll auf. | implementiert |
| `compliance-check` | Compliance | Compliance-Prüfung | Prüft ein Dokument, ein Verfahren oder den Arbeitsbereich gegen dokumentierte Anforderungen (Einstellungen, Rechtsgrundlagen, Aufbewahrungsregeln) und meldet Abweichungen. | implementiert |
| `gdpr-guard` | Compliance | DSGVO-Schutz | Findet personenbezogene Daten zu einem Kontakt, bewertet die Löschreife verknüpfter Dokumente und erstellt einen Löschplan. | implementiert |
| `retention` | Compliance | Aufbewahrung | Berechnet Aufbewahrungs- und Löschfristen für Dokumente nach den bestätigten Aufbewahrungsregeln und stellt die Liste der Löschkandidaten zusammen. | implementiert |
| `risk-flag` | Compliance | Risiko-Hinweise | Sucht nach anomalen Mustern mit den Radar-Regeln und den aktuellen Schwellen der Einstellungen, legt Befunde ab und benachrichtigt die Verantwortlichen. | implementiert |
| `anomaly` | Analyse | Anomalie-Erkennung | Erkennt Abweichungen vom gewohnten Verlauf mit einfachen Statistiken: je Tagesreihe (Dokumente, Aufgaben, Rechnungen, Zahlungen, Kontovolumen) werden Mittelwert und Standardabweichung berechnet und Tage außerhalb von Mittelwert ± k·Sigma gemeldet. | implementiert |
| `forecast` | Analyse | Prognosen | Prognostiziert den Trend der Tagesreihen (Dokumente, Aufgaben, Rechnungen, Zahlungen, Kontovolumen) per linearer Näherung über die Historie und liefert für die nächsten Tage einen Wertebereich (Prognosewert ± 1σ der Residuen) samt Vorbehalten. | implementiert |
| `insight` | Analyse | Auswertungen | Wertet die gesammelten Kennzahlen nach festen Regeln aus und formuliert verständliche Schlussfolgerungen mit Schweregrad (info/warning/critical) und konkreten Empfehlungen — ohne externes KI-System. | implementiert |
| `metric-collect` | Analyse | Kennzahlen-Sammlung | Sammelt Kennzahlen aus den verbundenen Systemen (aktivierte Integrationen sowie die Module Dokumente, Aufgaben, Rechnungen, Banking) und führt sie zu einem einheitlichen Set mit Quelle, Kennzahl, Wert und Einheit zusammen. | implementiert |
| `report-daily` | Analyse | Tagesberichte | Erstellt den Tagesbericht eines Tages: neue Dokumente und Aufgaben, Fristen, fällige Rechnungen, ausgeführte Zahlungen und Kontobewegungen, ergänzt um Ausgaben und offene Forderungen aus dem Berichtsmodul. | implementiert |
| `crm-activity` | CRM | Aktivitäten-Protokoll | Protokolliert Aktivitäten (Anruf, Brief, E-Mail, Termin) automatisch im Vorgang und am Kontakt. | implementiert |
| `crm-contact` | CRM | Kontaktpflege | Ergänzt einen Kontakt mit Daten aus Korrespondenz und Dokumenten, ohne Dubletten. | implementiert |
| `crm-deal` | CRM | Deal-Pipeline | Bewegt Deals regelbasiert durch die Stufen und markiert Stillstand. | implementiert |
| `crm-followup` | CRM | Follow-up | Plant Wiederholungskontakte regelbasiert und legt Erinnerungen als Aufgaben und Benachrichtigungen an. | implementiert |
| `crm-lead` | CRM | Lead-Erfassung | Nimmt Anfragen (Text oder Formular) an und legt daraus einen Lead als Kontakt mit Quelle an. | implementiert |
| `api-bridge` | Integration | API-Brücke | Überträgt Daten zwischen Systemen über bestehende Anbindungen: Routing nach Regeln, jede Übertragung wird protokolliert. | implementiert |
| `data-sync` | Integration | Daten-Synchronisation | Vergleicht die Datenstände zwischen den Systemen, findet Abweichungen und erstellt einen Synchronisationsplan. | implementiert |
| `monitor-systems` | Integration | System-Monitoring | Prüft die Verfügbarkeit verbundener Anbindungen und Webhooks anhand des letzten Erfolgs und der Fehler, erstellt einen Bericht und benachrichtigt bei Problemen. | implementiert |
| `marketing-content-brief` | Marketing | Content-Briefing | Schreibt aus einem Thema und einer Zielgruppe ein vollständiges Content-Briefing: Suchintention, Gliederung, Kernbegriffe, Quellen und Abnahmekriterien. | implementiert |
| `marketing-lead-nurture` | Marketing | Lead-Nurturing | Ordnet neue Kontakte einer Käuferphase zu und schlägt die nächste passende Maßnahme vor: Information, Fallbeispiel, Gesprächsangebot oder Pause. | implementiert |
| `marketing-newsletter` | Marketing | Newsletter-Kampagne | Plant und prüft Newsletter-Ausgaben: Zielgruppe, Betreffzeilen-Varianten, Inhaltsblöcke und Versandzeitpunkt. | implementiert |
| `marketing-sea-campaign` | Marketing | SEA-Kampagnensteuerung | Plant und bewertet Google-Ads-Kampagnen: Budgets, Gebotsstrategien, Keyword-Sets und Anzeigentext-Varianten. | implementiert |
| `marketing-seo-audit` | Marketing | SEO-Audit | Prüft die wichtigsten SEO-Signale einer Website anhand der übergebenen Seitenliste: Seitentitel, Meta-Beschreibungen, Überschriftenstruktur und interne Verlinkung. | implementiert |
| `marketing-social-calendar` | Marketing | Social-Media-Redaktionsplan | Erstellt aus Anlässen, Produkten und Zielgruppen einen Redaktionsplan für die nächsten Wochen. | implementiert |
| `sales-followup-cadence` | Vertrieb | Vertriebs-Follow-up-Kadenz | Legt für offene Angebote und Gespräche eine Nachfass-Kadenz mit Terminen und Kanal fest. | implementiert |
| `sales-offer-draft` | Vertrieb | Angebotserstellung | Erstellt aus Anfrage, Preisliste und Leistungsumfang einen Angebotsentwurf mit Positionen, Rabatten und Gültigkeit. | implementiert |
| `sales-pipeline-review` | Vertrieb | Vertriebs-Pipeline-Review | Prüft die offenen Verkaufschancen auf Stillstand, fehlende nächste Schritte und unrealistische Abschlussdaten. | implementiert |
| `sales-price-book` | Vertrieb | Preisbuch-Pflege | Hält Preise, Staffelungen und Rabattgrenzen konsistent und prüft Änderungen gegen bestehende Angebote und Verträge. | implementiert |
| `sales-win-loss` | Vertrieb | Gewonnen-Verloren-Analyse | Wertet gewonnene und verlorene Verkaufschancen nach Grund, Branche, Größe und Verkaufsphase aus. | implementiert |
| `hr-leave-request` | Personal | Urlaubsanträge | Prüft Urlaubsanträge gegen Restanspruch, Teamkalender und Sperrzeiten und bereitet die Entscheidung vor. | implementiert |
| `hr-onboarding` | Personal | Onboarding | Erzeugt aus Startdatum und Rolle einen Onboarding-Plan mit Aufgaben für IT-Zugang, Vertragsunterlagen, Einarbeitung und Probezeitgespräch. | implementiert |
| `hr-payroll-data` | Personal | Lohndaten-Vorbereitung | Stellt die Lohndaten für den Monat zusammen: Arbeitszeiten, Abwesenheiten, Zuschläge und Änderungen an Stammdaten. | implementiert |
| `hr-recruiting-screening` | Personal | Bewerber-Screening | Vergleicht eingegangene Bewerbungen strukturiert mit den Anforderungen der Stelle: Muss-Kriterien, Wunschkriterien und offene Fragen. | implementiert |
| `hr-time-tracking` | Personal | Zeiterfassungs-Prüfung | Prüft erfasste Arbeitszeiten auf fehlende Tage, unplausible Pausen und Überschreitungen der gesetzlichen Grenzen. | implementiert |
| `hr-training` | Personal | Schulungsplanung | Ermittelt aus Rollen, Pflichtschulungen und Fristen den Schulungsbedarf je Mitarbeitendem. | implementiert |
| `banking-fee-audit` | Bank & Zahlungsverkehr | Kontogebühren-Audit | Analysiert Kontobewegungen auf wiederkehrende Gebühren, Entgelte und ungewöhnliche Belastungen. | implementiert |
| `banking-payment-order` | Bank & Zahlungsverkehr | Zahlungsaufträge | Bereitet Zahlungsaufträge aus freigegebenen Eingangsrechnungen vor: Empfänger, IBAN-Prüfung, Betrag, Fälligkeit und Verwendungszweck. | implementiert |
| `banking-reconcile` | Bank & Zahlungsverkehr | Bankabgleich | Gleicht gebuchte Zahlungen mit offenen Rechnungen ab und schlägt Zuordnungen nur bei eindeutigem Treffer vor. | implementiert |
| `banking-statement-import` | Bank & Zahlungsverkehr | Kontoauszug-Import | Nimmt Kontoauszüge im CAMT- oder CSV-Format an, prüft Format und Zeitraum und bereitet den Import vor. | implementiert |
| `legal-audit-prep` | Recht & Datenschutz | Audit-Vorbereitung | Stellt für ein internes oder externes Audit die geforderten Nachweise zusammen: Richtlinien, Freigaben, Prüfprotokolle und offene Maßnahmen. | implementiert |
| `legal-contract-control` | Recht & Datenschutz | Vertragskontrolle | Überwacht Vertragslaufzeiten, Kündigungsfristen und offene Vertragspflichten im gewählten Zeitfenster. | implementiert |
| `legal-gdpr-request` | Recht & Datenschutz | DSGVO-Anfrage | Nimmt Auskunfts-, Export-, Berichtigungs- und Löschbegehren zu einer Person auf und prüft den Vorgang anhand der vorhandenen Daten. | implementiert |
| `legal-litigation-file` | Recht & Datenschutz | Streitfall-Akte | Baut aus Vorgang, Korrespondenz, Rechnungen und Fristen eine Streitfall-Akte mit Chronologie auf. | implementiert |
| `legal-nda-check` | Recht & Datenschutz | NDA-Prüfung | Prüft übermittelte Geheimhaltungsvereinbarungen gegen eine hinterlegte Musterklausel-Liste: Laufzeit, Vertragsstrafe, Ausnahmen und Gerichtsstand. | implementiert |
| `legal-sanctions-kyc` | Recht & Datenschutz | Sanktions- und KYC-Prüfung | Prüft Geschäftspartner gegen hinterlegte Sanktions- und PEP-Listen sowie gegen die eigenen KYC-Unterlagen. | implementiert |
| `logistics-customs` | Logistik | Zollanmeldung | Prüft Sendungsdaten auf die für die Zollanmeldung nötigen Angaben: Warenbeschreibung, Warenwert, Ursprungsland, Zolltarifnummer und Incoterm. | implementiert |
| `logistics-delivery-tracking` | Logistik | Lieferverfolgung | Überwacht offene Bestellungen und Liefertermine und meldet Überschreitungen mit Verzugstagen. | implementiert |
| `logistics-inventory-count` | Logistik | Inventur | Bereitet Inventuren vor: Zähllisten je Lagerort, Stichtag, Verantwortliche und Differenzprotokoll. | implementiert |
| `logistics-order-intake` | Logistik | Auftragserfassung | Erfasst eingehende Bestellungen aus E-Mail, Portal oder Datei und prüft Artikel, Mengen, Lieferadresse und Termin. | implementiert |
| `logistics-route-planning` | Logistik | Tourenplanung | Schlägt aus Lieferadressen, Zeitfenstern und Fahrzeugkapazitäten eine Tourenfolge vor. | implementiert |
| `logistics-supplier-scorecard` | Logistik | Lieferantenbewertung | Bewertet Lieferanten nach Termintreue, Vollständigkeit, Reklamationsquote und Reaktionszeit aus den vorhandenen Belegen. | implementiert |
| `support-escalation` | Kundensupport | Eskalation | Erkennt aus Fristsignalen, Wiederholungen und Tonlage eskulationsbedürftige Vorgänge und schlägt die nächste Stufe vor. | implementiert |
| `support-faq-suggest` | Kundensupport | FAQ-Vorschläge | Findet wiederkehrende Fragen in Support-Anfragen und schlägt daraus FAQ-Einträge mit Antwortentwurf vor. | implementiert |
| `support-nps` | Kundensupport | NPS-Umfragen | Bereitet NPS-Umfragen vor, wertet Rückläufe aus und gruppiert Freitextantworten nach Themen. | implementiert |
| `support-review-response` | Kundensupport | Bewertungsantworten | Entwirft Antworten auf öffentliche Bewertungen, getrennt nach Lob, sachlicher Kritik und berechtigtem Ärger. | implementiert |
| `support-sla-report` | Kundensupport | SLA-Bericht | Berichtet Erstreaktion, Lösungszeit und Verletzungen der Service-Level je Kanal und Team. | implementiert |
| `support-ticket` | Kundensupport | Ticket-Erfassung | Nimmt Anfragen aus E-Mail, Formular oder Chat an, ordnet sie einem Thema und einer Dringlichkeit zu und legt daraus eine bearbeitbare Aufgabe an. | implementiert |
| `ecommerce-catalog-sync` | E-Commerce | Katalog-Synchronisation | Gleicht Produktstammdaten zwischen Shop, ERP und Marktplätzen ab: Artikelnummer, Titel, Varianten, Maße und Kategorien. | implementiert |
| `ecommerce-marketplace-feed` | E-Commerce | Marketplace-Feed | Erzeugt und prüft Produktfeeds für Marktplätze: Pflichtfelder, Kategoriezuordnung, Bilder und Lagerstatus. | implementiert |
| `ecommerce-order-sync` | E-Commerce | Bestell-Synchronisation | Importiert Bestellungen aus Shop und Marktplätzen, prüft Zahlungsstatus, Lieferadresse und Verfügbarkeit und legt Fulfillment-Aufgaben an. | implementiert |
| `ecommerce-payment-check` | E-Commerce | Zahlungsprüfung | Prüft offene und fehlgeschlagene Zahlungen, ordnet sie Bestellungen zu und schlägt Zahlungserinnerung oder Storno vor. | implementiert |
| `ecommerce-price-monitor` | E-Commerce | Preis-Monitor | Beobachtet Wettbewerbspreise und die eigene Marge je Artikel und schlägt Preisanpassungen innerhalb der hinterlegten Grenzen vor. | implementiert |
| `ecommerce-returns` | E-Commerce | Retourenbearbeitung | Nimmt Retourenanmeldungen an, prüft Fristen und Gründe und bereitet Erstattung oder Umtausch vor. | implementiert |
| `production-material-plan` | Produktion & Handwerk | Materialplanung | Ermittelt den Materialbedarf je Auftrag, vergleicht ihn mit Lagerbestand und offenen Bestellungen und meldet Fehlmengen. | implementiert |
| `production-quality-check` | Produktion & Handwerk | Qualitätsprüfung | Prüft Qualitätsmeldungen und Messwerte gegen die hinterlegten Toleranzen und leitet daraus Prüf- und Nacharbeitsaufgaben ab. | implementiert |
| `production-quote-calc` | Produktion & Handwerk | Fertigungskalkulation | Kalkuliert Fertigungsangebote aus Materialkosten, Arbeitszeit, Maschinenstunden und Gemeinkostenzuschlag. | implementiert |
| `production-scheduling` | Produktion & Handwerk | Terminplanung | Plant Fertigungsaufträge auf Maschinen und Mitarbeitende unter Berücksichtigung von Terminen, Rüstzeiten und Abwesenheiten. | implementiert |
| `production-warranty` | Produktion & Handwerk | Garantiefall | Nimmt Garantiemeldungen auf, prüft Kaufdatum, Garantiefrist und bisherige Reparaturen und bereitet die Entscheidung vor. | implementiert |
| `production-work-order` | Produktion & Handwerk | Fertigungsauftrag | Erzeugt aus einem Auftrag einen Fertigungsauftrag mit Arbeitsfolgen, Materialbedarf und Prüfpunkten. | implementiert |
| `realestate-listing` | Immobilien | Objekt-Exposé | Erstellt aus Objektdaten ein Exposé mit Beschreibung, Ausstattung, Lage, Energieangaben und Preis. | implementiert |
| `realestate-maintenance` | Immobilien | Objektwartung | Nimmt Schadensmeldungen und Wartungsbedarf auf, ordnet sie der Gewerkeart zu und bereitet Aufträge an Dienstleister vor. | implementiert |
| `realestate-rent-dunning` | Immobilien | Mietrückstände | Erkennt offene Mietzahlungen und bereitet gestaffelte Zahlungserinnerungen vor. | implementiert |
| `realestate-tenant-admin` | Immobilien | Mieterverwaltung | Verwaltet Mieterwechsel und Stammdaten: Einzug, Auszug, Kaution, Nebenkostenvorauszahlung und Ansprechpartner. | implementiert |
| `realestate-viewing` | Immobilien | Besichtigungstermine | Koordiniert Besichtigungstermine zwischen Interessenten, Makler und Mietern und schlägt Zeitfenster vor. | implementiert |
| `hospitality-event-plan` | Gastronomie & Hotellerie | Veranstaltungsplanung | Plant Veranstaltungen von der Anfrage bis zur Nachbereitung: Raum, Technik, Menü, Personal und Zeitplan. | implementiert |
| `hospitality-menu-cost` | Gastronomie & Hotellerie | Speisekalkulation | Kalkuliert Speisen und Getränke aus Rezepturen, Einkaufspreisen und Schwund und zeigt Deckungsbeiträge je Gericht. | implementiert |
| `hospitality-no-show` | Gastronomie & Hotellerie | No-Show-Nachfassung | Erkennt nicht wahrgenommene Reservierungen und bereitet eine sachliche Nachfassnachricht mit Terminvorschlag vor. | implementiert |
| `hospitality-reservation` | Gastronomie & Hotellerie | Reservierungen | Nimmt Reservierungen an, prüft Verfügbarkeit, Öffnungszeiten und Kapazität und bestätigt oder schlägt Alternativen vor. | implementiert |
| `hospitality-shift-plan` | Gastronomie & Hotellerie | Dienstplan | Erstellt Dienstpläne aus Öffnungszeiten, Reservierungslage und Verfügbarkeiten der Mitarbeitenden. | implementiert |
| `healthcare-appointment` | Gesundheit & Pflege | Terminvergabe | Nimmt Terminanfragen an, prüft Sprechzeiten, Terminart und Dauer und schlägt freie Slots vor. | implementiert |
| `healthcare-billing` | Gesundheit & Pflege | Leistungsabrechnung | Bereitet die Abrechnung von Leistungen anhand der hinterlegten Gebührenordnung und erbrachten Positionen vor. | implementiert |
| `healthcare-documentation` | Gesundheit & Pflege | Dokumentations-Assistenz | Strukturiert Praxis- und Pflegedokumentation nach den hinterlegten Feldern und macht fehlende Angaben sichtbar. | implementiert |
| `healthcare-recall` | Gesundheit & Pflege | Recall-Erinnerungen | Ermittelt aus hinterlegten Fristen und Einwilligungen, wer eine Kontroll- oder Impferinnerung erhalten soll. | implementiert |
| `healthcare-shift-handover` | Gesundheit & Pflege | Schichtübergabe | Erstellt ein Übergabeprotokoll mit offenen Aufgaben, Besonderheiten und Zuständigkeiten für die nächste Schicht. | implementiert |
| `it-access-review` | IT & Betrieb | Zugriffsprüfung | Prüft aktive Konten und Rollen auf fehlende Nutzung, abgelaufene Befristungen und zu weit gehende Rechte. | implementiert |
| `it-asset-inventory` | IT & Betrieb | Asset-Inventar | Führt Geräte, Lizenzen und Verantwortlichkeiten zusammen und erkennt Lücken bei Zuordnung, Garantie und Vertragslaufzeit. | implementiert |
| `it-backup-check` | IT & Betrieb | Backup-Prüfung | Prüft die dokumentierten Backup-Läufe auf Ausfälle, Lücken und nicht getestete Wiederherstellungen. | implementiert |
| `it-incident` | IT & Betrieb | Störungsmeldung | Nimmt Störungsmeldungen an, klassifiziert Schweregrad und betroffene Systeme und legt einen nachvollziehbaren Incident an. | implementiert |
| `it-patch-window` | IT & Betrieb | Patch-Fenster | Plant Patch-Fenster anhand von Schweregrad, Wartungsfenstern und betroffenen Systemen und bereitet die Kommunikation vor. | implementiert |
| `knowledge-base` | Wissen & Reporting | Wissensartikel | Pflegt interne Wissensartikel mit Gültigkeitsdatum, Verantwortlichkeit und Quelle. | implementiert |
| `knowledge-kpi-report` | Wissen & Reporting | KPI-Bericht | Erstellt einen monatlichen KPI-Bericht aus den vorhandenen Kennzahlen: Dokumente, Aufgaben, Fristen, Rechnungen und Zahlungen. | implementiert |
| `knowledge-meeting-memory` | Wissen & Reporting | Besprechungsgedächtnis | Hält Entscheidungen, offene Punkte und Zuständigkeiten aus Besprechungsnotizen fest und verknüpft sie mit Vorgängen und Aufgaben. | implementiert |
| `knowledge-policy-digest` | Wissen & Reporting | Richtlinien-Zusammenfassung | Fasst geänderte Richtlinien und Regelungen in einer verständlichen Zusammenfassung mit betroffenen Rollen und Fristen zusammen. | implementiert |
| `knowledge-search-answer` | Wissen & Reporting | Wissenssuche | Beantwortet interne Fragen mit belegten Fundstellen aus Dokumenten und Wissensartikeln. | implementiert |

## Implementierte Agenten (Manifest + Bean)

Jeder der 126 Agenten ist Manifest **und** Spring-Bean; alle 30 Konstellationen sind damit vollständig implementiert. Die erste Welle umfasste acht Agenten, die zweite 41, die dritte 15 Branchen- sowie 13 Wissens-, Rechts-, Immobilien-, Gastronomie- und Gesundheitsagenten. `legal-audit-prep` war in Welle 1 und Welle 2b doppelt vorhanden; es bleibt die vollständigere Implementierung aus Welle 2b (Nachweis-Checkliste, Artefakt mit Quellschlüssel, Aufgaben mit Abschlusskriterium). Die folgende Tabelle erklärt stellvertretend die acht Agenten der ersten Welle:

| ID | Modul | Was die Bean wirklich tut |
|---|---|---|
| `legal-gdpr-request` | Datenschutz (`DataSubjectService`) | Auskunft/Export werden erstellt und im Anfragejournal dokumentiert; Berichtigung und Löschung nur als freigabepflichtiger Plan, Löschung wird bei Legal Hold verweigert. |
| `legal-contract-control` | Verträge (`ContractRepository`, `ContractObligationRepository`) | Liest endende Verträge und offene Pflichten im Zeitfenster, berechnet Verzugstage, ändert nichts. |
| `banking-fee-audit` | Bank (`bank_transactions`) | Findet Gebührenbuchungen über Schlüsselwörter, gruppiert je Empfänger und summiert; bucht nichts. |
| `logistics-delivery-tracking` | Bestellungen (`purchase_orders`) | Meldet überfällige und kurzfristig fällige Bestellungen mit Verzugstagen; ändert keine Bestellung. |
| `support-ticket` | Aufgaben (`TaskService`) | Klassifiziert Thema/Priorität/SLA nach festen Regeln und legt genau eine Aufgabe an; erkennt offene Dubletten. |
| `it-access-review` | Identität (`users`, `memberships`, Rollen, MFA) | Prüft Konten auf fehlende Nutzung, abgelaufene Befristung, fehlende Rolle und fehlende MFA; entzieht keine Rechte. |
| `knowledge-kpi-report` | Reporting (`ReportService`) | Baut den KPI-Bericht für den letzten vollständigen Monat (oder einen expliziten Zeitraum) mit Quelle und Genauigkeitskennzeichen je Kennzahl. |
| `hr-onboarding` | Aufgaben (`TaskService`) | Legt den datierten Onboarding-Plan an, idempotent über den Aufgabentitel; legt keine doppelten Aufgaben an. |

## Pflege und Tests

- `AgentCatalogTest` prüft: IDs eindeutig und kebab-case, Kategorien aus `AgentRegistry.CATEGORIES`, alle Kategorien belegt, neue Manifeste mit Funktionen/Triggern/Inputs/Systemen/Version/Status.
- `ConstellationCatalogTest` prüft die 30 Workflow-Vorlagen: eindeutige IDs, lückenlose Schrittfolge, jeder `agentId` existiert im Katalog, DE/RU-Namen und Ergebnisse vorhanden — und jeder Schritt trägt den Status `ACTIVE`.
- `ConstellationsImplementedIT` prüft dieselbe Zusage am laufenden Spring-Kontext: für jeden der 30 Presets muss jede `agentId` eine Bean im `AgentRegistry` haben.
- `AgentRegistryIT` prüft den Katalog zur Laufzeit: 126 Manifeste, 126 Beans, keine ID ohne Implementierung und kein Status außer `ACTIVE`. Damit fällt auch ein doppelt implementierter Agent auf, den die ID-Zuordnung des Registry sonst stillschweigend überschreiben würde.
- `AllAgentsSmokeIT` ruft jeden der 126 Agenten mit `trigger=smoke`, leerem Tenant und ohne Eingaben auf, in einer eigenen Transaktion mit Tenant-Kontext, und schreibt den Lauf nach `target/agent-smoke-report.json` und [AGENT_VERIFICATION.md](AGENT_VERIFICATION.md). Er ist grün, solange kein Agent eine Ausnahme wirft; ein REFUSED ohne Daten ist das erwartete Verhalten.
