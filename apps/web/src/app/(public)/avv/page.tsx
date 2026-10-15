'use client';

import Link from 'next/link';
import { H2, LegalShell, Op, useLegal } from '@/legal/legal-shell';

function Body() {
  const { operator, retentionDays } = useLegal();
  return (
    <>
      <p>
        Dieser Vertrag regelt die Verarbeitung personenbezogener Daten im Auftrag nach Art. 28 DSGVO zwischen dem Kunden (Verantwortlicher) und
        <Op field="name" /> (Auftragsverarbeiter). Unternehmen, Selbständige und andere Kunden, die über die Plattform Daten Dritter verarbeiten,
        schließen ihn mit der Registrierung (Bestätigung des Kästchens zur Auftragsverarbeitung); die Version und der Zeitpunkt werden festgehalten.
      </p>
      <H2>1. Gegenstand und Dauer</H2>
      <p>
        Gegenstand ist die Bereitstellung der Plattform „Bürokratie-Copilot“ (Erfassen, Speichern, Einordnen und Verwalten von Dokumenten,
        Nachrichten, Kontakten, Verträgen, Rechnungen und Aufgaben). Die Dauer richtet sich nach der Laufzeit des Nutzungsvertrags; die Pflichten
        zur Rückgabe und Löschung (Ziffer 9) gelten darüber hinaus.
      </p>
      <H2>2. Art und Zweck der Verarbeitung, Datenarten, Betroffene</H2>
      <ul>
        <li><strong>Art:</strong> Erheben, Speichern, Ordnen, Auslesen (Texterkennung), Abfragen, Verknüpfen, Übermitteln an den Kunden, Löschen.</li>
        <li><strong>Zweck:</strong> Unterstützung der Verwaltungsvorgänge des Kunden.</li>
        <li><strong>Datenarten:</strong> Stammdaten und Kontaktdaten, Vertrags- und Rechnungsdaten, Korrespondenz, Inhalte hochgeladener Dokumente; je nach Nutzung auch besondere Kategorien (z. B. Gesundheitsdaten) – deren Ablage entscheidet der Kunde.</li>
        <li><strong>Betroffene:</strong> Mitarbeiter, Kunden, Lieferanten, Familienangehörige, Korrespondenzpartner und weitere Personen, deren Daten der Kunde ablegt.</li>
      </ul>
      <H2>3. Weisungen</H2>
      <p>
        Der Auftragsverarbeiter verarbeitet die Daten nur auf dokumentierte Weisung des Kunden; der Nutzungsvertrag und die Bedienung der Plattform
        sind die Weisung. Hält er eine Weisung für rechtswidrig, weist er den Kunden darauf hin. Eine Verarbeitung für eigene Zwecke findet nicht
        statt.
      </p>
      <H2>4. Vertraulichkeit</H2>
      <p>Alle Personen, die Zugang zu den Daten haben, sind zur Vertraulichkeit verpflichtet.</p>
      <H2>5. Technische und organisatorische Maßnahmen (Art. 32 DSGVO)</H2>
      <ul>
        <li>Zutritt und Zugang: Server in einem Rechenzentrum mit Zutrittskontrolle (<Op field="hosting" />); Anmeldung mit Passwort-Richtlinie und optionalem zweiten Faktor; Sperrung nach Fehlversuchen.</li>
        <li>Zugriff: rollenbasierte Rechte; strikte Trennung der Arbeitsbereiche durch Zeilen-Sicherheit in der Datenbank (ein Arbeitsbereich sieht keine Daten eines anderen).</li>
        <li>Weitergabe: Übertragung ausschließlich verschlüsselt (HTTPS); externe KI-Verarbeitung standardmäßig abgeschaltet.</li>
        <li>Speicherung: Dokumente verschlüsselt im Ruhezustand (AES-256-GCM, ein Schlüssel pro Arbeitsbereich, durch einen Plattformschlüssel geschützt); Prüfung hochgeladener Dateien auf Schadsoftware.</li>
        <li>Eingabe und Nachvollziehbarkeit: Änderungsprotokoll mit Person und Zeitpunkt; Protokoll der Zugriffe auf Dokumente, das über die Anwendung nicht geändert werden kann.</li>
        <li>Verfügbarkeit: Datensicherung (Backup) und dokumentiertes Wiederherstellungsverfahren; Gesundheitsprüfungen der Dienste.</li>
        <li>Trennung und Begrenzung: Zwecktrennung nach Arbeitsbereichen; Begrenzung der Anfragenzahl gegen Missbrauch.</li>
        <li>Überprüfung: automatisierte Tests der Mandantentrennung und der Rechte bei jeder Änderung.</li>
      </ul>
      <H2>6. Unterauftragsverarbeiter</H2>
      <p>
        Der Kunde stimmt dem Einsatz der folgenden Unterauftragsverarbeiter zu. Der Auftragsverarbeiter verpflichtet sie vertraglich auf die
        Pflichten dieses Vertrags und informiert den Kunden vorab über jede beabsichtigte Änderung; der Kunde kann aus wichtigem Grund
        widersprechen.
      </p>
      <p style={{ whiteSpace: 'pre-line' }}>{operator.subProcessors ?? operator.hosting ?? 'Noch nicht hinterlegt.'}</p>
      <H2>7. Unterstützung des Kunden</H2>
      <p>
        Der Auftragsverarbeiter unterstützt den Kunden bei Anfragen Betroffener (Art. 15–22 DSGVO), bei der Datensicherheit, bei Meldungen von
        Verletzungen des Schutzes personenbezogener Daten (Art. 33, 34) und bei Datenschutz-Folgenabschätzungen (Art. 35, 36). Die Plattform enthält
        dafür Werkzeuge (Auskunft, Berichtigung, Export, eingeschränkte Löschung). Eine Verletzung, die den Auftragsverarbeiter betrifft, meldet er dem Kunden
        unverzüglich.
      </p>
      <H2>8. Kontrollrechte</H2>
      <p>
        Der Auftragsverarbeiter stellt dem Kunden die zum Nachweis der Pflichten nötigen Informationen bereit und ermöglicht Überprüfungen
        einschließlich Inspektionen, die der Kunde oder ein von ihm beauftragter Prüfer durchführt, nach rechtzeitiger Anmeldung und ohne
        Gefährdung anderer Kunden.
      </p>
      <H2>9. Rückgabe und Löschung</H2>
      <p>
        Nach Vertragsende gibt der Auftragsverarbeiter die Daten heraus (Export) und löscht sie danach, spätestens nach {retentionDays} Tagen
        Aufbewahrung, soweit keine gesetzliche Pflicht zur Speicherung besteht.
      </p>
      <H2>10. Schlussbestimmungen</H2>
      <p>
        Es gelten die <Link href="/agb">Allgemeinen Geschäftsbedingungen</Link>; bei Widersprüchen zum Datenschutz geht dieser Vertrag vor. Es gilt
        deutsches Recht.
      </p>
    </>
  );
}

export default function ProcessingAgreementPage() {
  return (
    <LegalShell title="Vertrag zur Auftragsverarbeitung (AVV)">
      <Body />
    </LegalShell>
  );
}
