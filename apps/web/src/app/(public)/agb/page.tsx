'use client';

import Link from 'next/link';
import { H2, LegalShell, Op, useLegal } from '@/legal/legal-shell';

function Body() {
  const { trialDays, retentionDays } = useLegal();
  return (
    <>
      <p>
        Diese Allgemeinen Geschäftsbedingungen gelten für die Nutzung der Plattform „Bürokratie-Copilot“ von <Op field="name" /> (Anbieter).
      </p>
      <H2>1. Leistung</H2>
      <p>
        Der Anbieter stellt eine Online-Plattform bereit, mit der Nutzer Dokumente und Post erfassen, einordnen, Fristen und Aufgaben verwalten,
        Verträge und Rechnungen führen und Antworten vorbereiten. Der Funktionsumfang richtet sich nach dem gewählten Tarif und der
        Leistungsbeschreibung auf der Webseite. Die Plattform unterstützt Verwaltungsvorgänge; sie ist <strong>keine Rechts-, Steuer- oder
        Anlageberatung</strong>. Vorschläge der Plattform (Dokumenttypen, Fristen, Hinweise, Entwürfe) sind Hilfen, die der Nutzer prüft; die
        Verantwortung für Entscheidungen und für das Einhalten von Fristen bleibt beim Nutzer.
      </p>
      <H2>2. Vertragsschluss, Konto</H2>
      <p>
        Der Vertrag kommt mit der Registrierung und der Bestätigung der E-Mail-Adresse zustande. Der Nutzer muss wahrheitsgemäße Angaben machen,
        seine Zugangsdaten geheim halten und uns bei Verdacht auf Missbrauch unverzüglich informieren. Ein Konto ist nicht übertragbar; mehrere
        Personen eines Arbeitsbereichs erhalten eigene Zugänge.
      </p>
      <H2>3. Testzeitraum, Tarife, Preise</H2>
      <p>
        Neue Arbeitsbereiche beginnen mit einem kostenlosen Test von {trialDays} Tagen. Danach ist ein Tarif nötig, um den Arbeitsbereich weiter zu
        nutzen. Tarife, Leistungsumfang und Preise stehen auf der Webseite; alle Preise sind Endpreise einschließlich der gesetzlichen
        Umsatzsteuer, soweit sie anfällt. Gebühren Dritter für Zahlungswege trägt der Nutzer.
      </p>
      <H2>4. Zahlung, Laufzeit, Kündigung</H2>
      <p>
        Der Nutzer wählt eine Laufzeit und zahlt im Voraus per Überweisung oder auf einem anderen angebotenen Weg. Mit der Rechnung kann der
        Zugang für den bezahlten Zeitraum freigeschaltet werden. Eine automatische Verlängerung findet nicht statt, es sei denn, sie wurde bei der
        Bestellung ausdrücklich vereinbart. Das Recht zur außerordentlichen Kündigung aus wichtigem Grund bleibt unberührt.
      </p>
      <H2>5. Pflichten des Nutzers</H2>
      <p>
        Der Nutzer legt nur Inhalte ab, zu deren Verarbeitung er berechtigt ist, und bleibt für sie verantwortlich; er beachtet insbesondere die
        Rechte Dritter und den Datenschutz. Verboten sind rechtswidrige Inhalte, das Einschleusen von Schadsoftware, das Umgehen von
        Sicherheitsmaßnahmen und eine übermäßige Belastung der Plattform. Der Nutzer sichert wichtige Originale selbst.
      </p>
      <H2>6. Verfügbarkeit</H2>
      <p>
        Der Anbieter schuldet eine Erreichbarkeit der Plattform nach dem Stand der Technik. Wartungsarbeiten werden nach Möglichkeit
        angekündigt. Zugesicherte Verfügbarkeiten gibt es nur, wenn sie ausdrücklich im Tarif genannt sind.
      </p>
      <H2>7. Datenschutz, Auftragsverarbeitung</H2>
      <p>
        Der Anbieter verarbeitet personenbezogene Daten nach der <Link href="/datenschutz">Datenschutzerklärung</Link>. Soweit er Daten im Auftrag
        eines Kunden verarbeitet, gilt der <Link href="/avv">Auftragsverarbeitungsvertrag</Link>, den Unternehmen mit der Registrierung
        abschließen.
      </p>
      <H2>8. Beendigung und Daten</H2>
      <p>
        Nach Ablauf eines Tarifs wird der Arbeitsbereich gesperrt und {retentionDays} Tage aufbewahrt. In dieser Zeit kann der Nutzer seine Daten
        exportieren oder die Löschung verlangen; danach löscht der Anbieter den Arbeitsbereich mit allen Inhalten. Gesetzliche Aufbewahrungspflichten
        (z. B. für Rechnungen des Anbieters) bleiben unberührt.
      </p>
      <H2>9. Haftung</H2>
      <p>
        Der Anbieter haftet unbeschränkt bei Vorsatz und grober Fahrlässigkeit, bei Verletzung von Leben, Körper und Gesundheit, nach dem
        Produkthaftungsgesetz und soweit er eine Garantie übernommen hat. Bei leicht fahrlässiger Verletzung wesentlicher Vertragspflichten
        (Pflichten, deren Erfüllung die ordnungsgemäße Durchführung des Vertrags erst ermöglicht und auf die der Nutzer regelmäßig vertrauen darf)
        ist die Haftung auf den vorhersehbaren, vertragstypischen Schaden begrenzt. Im Übrigen ist die Haftung bei leichter Fahrlässigkeit
        ausgeschlossen. Für den Verlust von Daten haftet der Anbieter nur in dem Umfang, der auch bei regelmäßiger, dem Risiko angemessener
        Datensicherung durch den Nutzer eingetreten wäre.
      </p>
      <H2>10. Änderungen</H2>
      <p>
        Der Anbieter kann diese Bedingungen und die Leistungen mit Wirkung für die Zukunft ändern, wenn das für den Nutzer zumutbar ist, und teilt
        Änderungen mindestens sechs Wochen vorher in Textform mit. Widerspricht der Nutzer nicht innerhalb dieser Frist, gelten die Änderungen;
        darauf weist der Anbieter in der Mitteilung hin. Die Änderung wesentlicher Leistungspflichten bedarf der Zustimmung.
      </p>
      <H2>11. Schlussbestimmungen</H2>
      <p>
        Es gilt deutsches Recht unter Ausschluss des UN-Kaufrechts; gegenüber Verbrauchern gilt es nur, soweit nicht zwingende Bestimmungen des
        Staates, in dem der Verbraucher seinen gewöhnlichen Aufenthalt hat, entgegenstehen. Ist der Nutzer Kaufmann, juristische Person des
        öffentlichen Rechts oder öffentlich-rechtliches Sondervermögen, ist Gerichtsstand der Sitz des Anbieters. Sollte eine Bestimmung
        unwirksam sein, bleibt der Rest wirksam. Verbrauchern steht ein <Link href="/widerruf">Widerrufsrecht</Link> zu.
      </p>
    </>
  );
}

export default function TermsPage() {
  return (
    <LegalShell title="Allgemeine Geschäftsbedingungen">
      <Body />
    </LegalShell>
  );
}
