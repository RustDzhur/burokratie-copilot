# Tarife, Zahlung und Löschung der Daten

Dieses Dokument beschreibt, wie die Plattform Zugang und Bezahlung regelt, und was der Betreiber einrichten muss.
Es ist keine Rechtsberatung: Texte für Website, AGB und Datenschutzerklärung sollte eine Fachperson prüfen.

## Tarife und Testphase

* Drei Tarife: **Privat** (7 €), **Familie** (12 €), **Firma** (29 €) pro Monat. Die Preise stehen in der Tabelle `plans`
  und werden im Admin-Bereich geändert, nicht im Code.
* Bei der Registrierung wählt man die Art des Kontos; daraus ergibt sich der Tarif. Der Inhaber kann ihn unter
  *Tarif und Zahlung* wechseln (gilt ab der nächsten Zahlung).
* Jeder neue Arbeitsbereich startet mit einer **kostenlosen Testphase von 60 Tagen** (`billing.trial_days`).
* Ein einziges Datum entscheidet über den Zugang: `subscriptions.access_until`. Die Testphase und jede Zahlung
  verschieben es; ein Administrator kann es von Hand verschieben.

## Was nach dem Ende des Zugangs geschieht

1. Ist `access_until` erreicht, wird das Abonnement **EXPIRED**. Jede API-Anfrage außer Anmeldung, MFA und dem
   Bereich `/api/v1/billing` antwortet mit **402**; die Oberfläche zeigt nur noch die Seite *Zugang beendet*.
2. Die Daten bleiben **100 Tage** (`billing.retention_days`) gesperrt erhalten. Der Inhaber bekommt eine E-Mail und
   entscheidet auf der Seite *Zugang beendet*: **Tarif verlängern**, **Daten aufbewahren** oder **Daten sofort löschen**
   (Bestätigung durch Eintippen von `DELETE`).
3. Erinnerungen: 14, 7 und 1 Tag vor dem Ende des Zugangs; 30, 7 und 1 Tag vor der automatischen Löschung.
4. Nach Ablauf der Aufbewahrungsfrist löscht der Stundenjob (`SubscriptionLifecycleJob`) den Arbeitsbereich
   **automatisch und unwiderruflich**: Zeilen (Fremdschlüssel mit `ON DELETE CASCADE`), Audit-Verlauf, Zugriffslogs,
   gespeicherte Dateien und Personen, die zu keinem anderen Arbeitsbereich gehören.
5. **Rechnungen bleiben erhalten** (`billing_invoices` hat bewusst keinen Fremdschlüssel auf `tenants`): sie sind
   Buchungsunterlagen mit eigener gesetzlicher Aufbewahrungsfrist.

Die Löschung auf Wunsch des Inhabers ist jederzeit möglich (*Tarif und Zahlung* → *Konto und alle Daten löschen*).

Die Audit- und Zugriffslogs sind sonst nur anhängbar (Trigger `forbid_append_only_mutation`). Ausnahme: ein `DELETE`
in einer Transaktion, die mit `set_config('app.tenant_purge', '<tenant-id>', true)` genau diesen Arbeitsbereich zur
Löschung freigibt (V33). Getestet in `BillingLifecycleIT`.

> **Offen, vor dem Start mit Fachperson klären:** Aufbewahrungsfrist von 100 Tagen und Löschkonzept in der
> Datenschutzerklärung nennen; Auftragsverarbeitung; Datenexport (Art. 20 DSGVO) – die Oberfläche bietet ihn noch nicht
> als Download an.

## Zahlung ohne Zahlungsanbieter

Zwei Wege, beide ohne Gebühren eines Dienstleisters:

* **Rechnung (Überweisung):** Der Kunde gibt Name/Firma, Anschrift, Land und optional USt-IdNr. ein. Die Plattform
  erzeugt die Rechnung `BC-<Jahr>-<laufende Nummer>` als PDF mit IBAN und Verwendungszweck.
* **Krypto-Wallet:** Die Rechnung nennt Währung, Netzwerk, Adresse und den Betrag (Euro ÷ Kurs, aufgerundet auf 6
  Stellen). Die Oberfläche zeigt den QR-Code der Adresse.

Eingänge erkennt die Plattform **nicht** selbst. Der Administrator bestätigt sie im Admin-Bereich → *Zahlungen* →
*Zahlung eingegangen*; erst das verlängert den Zugang (um die bezahlten Monate, Restzeit bleibt erhalten) und schickt
dem Kunden eine Bestätigung. Dieselbe Rechnung kann nicht zweimal bestätigt werden.

Eine Rechnung wird nur erstellt, wenn die Zahlungsdaten eingerichtet sind (Admin → *Zahlungsdaten*): Empfänger und
IBAN für Rechnungen, Währung/Netzwerk/Adresse/Kurs für Krypto. Drückt der Kunde zweimal auf den Knopf, bekommt er
dieselbe offene Rechnung zurück.

## Admin-Bereich (`/admin`)

Nur für die in `BC_PLATFORM_ADMINS` (in `infra/.env`) eingetragenen E-Mail-Adressen, **und** nur wenn die Adresse per
Link bestätigt ist. Durch Registrierung kann niemand Administrator werden. Der Bereich zeigt:

* **Übersicht:** Kunden, monatlich wiederkehrender Umsatz, Testphasen, die bald enden, offene Rechnungen.
* **Kunden:** suchen, Zugang um einen Monat verlängern, 14 Tage schenken, sperren/entsperren, Kunde löschen (Name
  zur Bestätigung eintippen).
* **Zahlungen:** offene Rechnungen bestätigen oder stornieren, PDF herunterladen.
* **Tarife:** Name und Preis.
* **Zahlungsdaten:** Empfänger, IBAN, Krypto-Wallet, Fristen, enthaltene Umsatzsteuer, Hinweis auf der Rechnung
  (z. B. Kleinunternehmer nach § 19 UStG).
* **Dienste:** Status der Dienste, die für alle gelten (KI, E-Mail, Virenprüfung, OCR, Speicher, Registrierung) und die
  Einstellung, die sie ändert. Diese werden in der Serverkonfiguration gesetzt, nicht in der Oberfläche.

Alle Aufrufe laufen im Systemkontext (ohne Mandant), weil sie über Mandanten hinweg lesen; das passiert vor Beginn der
Transaktion, damit die Datenbankverbindung ohne Mandant geöffnet wird.

## Sichtbarkeit je Kontotyp

Das Menü zeigt nur, was zum Kontotyp passt (`audience` in `navigation/screens.ts`): *Familie* nur im Familienkonto,
*Unternehmen* und *Mitarbeiter* nur im Firmenkonto. Das Menü ist keine Zugriffskontrolle; die Berechtigungen prüft
weiterhin der Server.

## Einrichtung auf dem Server

```bash
# infra/.env
BC_PLATFORM_ADMINS=ihre.adresse@example.de
```

Danach in der Oberfläche anmelden → *Administration* → *Zahlungsdaten* ausfüllen.
