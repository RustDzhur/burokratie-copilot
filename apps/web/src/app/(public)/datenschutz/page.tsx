'use client';

import Link from 'next/link';
import { H2, LegalShell, Op, useLegal } from '@/legal/legal-shell';

function Body() {
  const { retentionDays, trialDays, operator } = useLegal();
  const hasDpo = Boolean(operator.dpoName || operator.dpoEmail);
  return (
    <>
      <p>
        Der Schutz Ihrer personenbezogenen Daten ist uns wichtig. Hier erfahren Sie nach Art. 13 und 14 der Datenschutz-Grundverordnung
        (DSGVO), welche Daten wir verarbeiten, wozu, auf welcher Grundlage, wie lange und welche Rechte Sie haben.
      </p>

      <H2>1. Verantwortlicher und zwei Rollen</H2>
      <p>
        Verantwortlicher im Sinne der DSGVO ist: <Op field="name" />, <span style={{ whiteSpace: 'pre-line' }}><Op field="address" /></span>,
        E-Mail: <Op field="email" />.
      </p>
      <p>
        Wir haben zwei Rollen. Für die Daten Ihres <strong>Kontos</strong> (Registrierung, Anmeldung, Abrechnung, Sicherheit) sind wir selbst
        verantwortlich. Für die <strong>Inhalte Ihres Arbeitsbereichs</strong> (Dokumente, Nachrichten, Kontakte, Verträge, Rechnungen, Aufgaben)
        verarbeiten wir die Daten als Auftragsverarbeiter nach Art. 28 DSGVO im Auftrag des Kunden, der den Arbeitsbereich betreibt; dieser ist
        für diese Inhalte verantwortlich. Die Bedingungen regelt der <Link href="/avv">Auftragsverarbeitungsvertrag</Link>.
      </p>
      {hasDpo ? (
        <p>
          Datenschutzbeauftragter: {operator.dpoName ?? ''} {operator.dpoEmail ? <>· E-Mail: {operator.dpoEmail}</> : null}
        </p>
      ) : null}

      <H2>2. Welche Daten wir wozu verarbeiten</H2>
      <h3>2.1 Konto und Anmeldung</h3>
      <p>
        E-Mail-Adresse, Name, Passwort (nur als Hash, nie im Klartext), Sprache, Zeitzone, auf Wunsch ein Profilfoto, die Zeitpunkte von
        Anmeldungen, die Zugehörigkeit zu Arbeitsbereichen und Rollen sowie Ihre Zustimmung zu den Bedingungen (Dokument, Version, Zeitpunkt).
        Zweck: Vertragserfüllung, Zugang und Sicherheit. Rechtsgrundlage: Art. 6 Abs. 1 lit. b DSGVO; für Sicherheitsprotokolle lit. f
        (unser Interesse am Schutz des Dienstes und der Daten). Die Angabe ist für den Vertrag erforderlich; ohne sie können wir das Konto
        nicht führen.
      </p>
      <h3>2.2 Inhalte Ihres Arbeitsbereichs</h3>
      <p>
        Hochgeladene und per E-Mail empfangene Dokumente, deren Text und erkannte Angaben (z. B. Beträge, Fristen, Aktenzeichen), Kontakte,
        Verträge, Rechnungen, Aufgaben und Verläufe. Wir lesen, ordnen und erklären sie, um Ihre Verwaltungsvorgänge zu unterstützen.
        Die Dokumente werden mit einem Schlüssel pro Arbeitsbereich verschlüsselt gespeichert, auf Schadsoftware geprüft und per
        Texterkennung gelesen; diese Schritte laufen auf unserer eigenen Infrastruktur. Dabei können auch Daten Dritter (z. B. Absender,
        Rechnungsempfänger) und besondere Kategorien (z. B. Gesundheitsdaten in einem Arztbrief) enthalten sein; wir sortieren solche Dokumente
        nur auf Ihre Weisung ein und werten sie nicht für eigene Zwecke aus.
      </p>
      <h3>2.3 Künstliche Intelligenz</h3>
      <p>
        Die Erkennung von Dokumenttypen und Fristen arbeitet regelbasiert auf unserer eigenen Infrastruktur. Die Übermittlung von
        Dokumentinhalten an einen externen KI-Anbieter ist <strong>standardmäßig abgeschaltet</strong>; sie findet nur statt, wenn der
        Arbeitsbereich sie ausdrücklich einschaltet und eine Person sie im Einzelfall anstößt. KI-Ergebnisse sind Vorschläge, die eine Person
        prüft; wir treffen keine Entscheidungen, die Ihnen gegenüber rechtliche Wirkung entfalten oder Sie erheblich beeinträchtigen, allein
        automatisiert (Art. 22 DSGVO).
        {operator.thirdCountry ? <> {operator.thirdCountry}</> : null}
      </p>
      <h3>2.4 Zahlung und Abrechnung</h3>
      <p>
        Rechnungsdaten (Name, Anschrift, gewählter Tarif, Beträge, Zahlungseingang). Zweck: Vertragserfüllung und gesetzliche
        Aufbewahrungspflichten. Rechtsgrundlage: Art. 6 Abs. 1 lit. b und c DSGVO (§ 147 AO, § 257 HGB). Diese Unterlagen bewahren wir für die
        gesetzlichen Fristen auf, auch wenn das Konto früher endet.
      </p>
      <h3>2.5 Server-Protokolle und Sicherheit</h3>
      <p>
        Beim Aufruf verarbeitet der Server technisch notwendige Verbindungsdaten (IP-Adresse, Zeitpunkt, aufgerufene Adresse, Status). Zweck:
        Betrieb, Fehlersuche, Abwehr von Angriffen und Missbrauch (Begrenzung der Anfragenzahl). Rechtsgrundlage: Art. 6 Abs. 1 lit. f DSGVO.
        Die Verbindungsdaten werden nicht mit anderen Daten zusammengeführt, um Sie zu profilieren.
      </p>
      <h3>2.6 Kontaktaufnahme und E-Mail-Versand</h3>
      <p>
        Wenn Sie uns schreiben, verarbeiten wir Ihre Angaben zur Bearbeitung (Art. 6 Abs. 1 lit. b oder f DSGVO). Systemnachrichten
        (Bestätigung der E-Mail-Adresse, Einladungen, Passwort-Zurücksetzen, Erinnerungen) sind für den Vertrag erforderlich.
      </p>

      <H2>3. Cookies und Speicher im Browser</H2>
      <p>
        Wir setzen nur technisch notwendige Cookies und Browser-Speicher ein; Tracking oder Werbung gibt es nicht. Einzelheiten und die
        vollständige Liste stehen unter <Link href="/cookies">Cookies und Speicher</Link>. Rechtsgrundlage: § 25 Abs. 2 Nr. 2 TDDDG und
        Art. 6 Abs. 1 lit. b, f DSGVO.
      </p>

      <H2>4. Empfänger und Auftragsverarbeiter</H2>
      <p>
        Wir geben Daten nur weiter, soweit das für den Vertrag nötig ist oder eine Rechtspflicht besteht. Dienstleister, die in unserem
        Auftrag Daten verarbeiten (Hosting, E-Mail-Versand), sind nach Art. 28 DSGVO vertraglich gebunden.
      </p>
      <p>Hosting und Rechenzentrum: <Op field="hosting" /></p>
      {operator.subProcessors ? <p style={{ whiteSpace: 'pre-line' }}>Weitere Dienstleister: {operator.subProcessors}</p> : null}

      <H2>5. Übermittlung in Drittländer</H2>
      <p>
        {operator.thirdCountry
          ? operator.thirdCountry
          : 'Eine Übermittlung in Länder außerhalb der EU / des EWR findet nicht statt, es sei denn, der Arbeitsbereich schaltet einen externen KI-Anbieter ein; dann gelten die dafür geschlossenen Garantien (Art. 44 ff. DSGVO) und Sie werden vorab informiert.'}
      </p>

      <H2>6. Speicherdauer</H2>
      <ul>
        <li>Kontodaten und Inhalte des Arbeitsbereichs: solange das Konto besteht. Nach Ablauf eines Tarifs ist der Arbeitsbereich gesperrt, aber {retentionDays} Tage erhalten; in dieser Zeit können Sie Ihre Daten exportieren oder die Löschung verlangen. Danach löschen wir den Arbeitsbereich mit allen Inhalten.</li>
        <li>Rechnungs- und Buchungsunterlagen: nach den gesetzlichen Fristen (§ 147 AO, § 257 HGB).</li>
        <li>Zustimmungsnachweise: solange das Konto besteht.</li>
        <li>Sicherheits- und Server-Protokolle: nur so lange, wie es für die genannten Zwecke nötig ist.</li>
        <li>Testzeitraum: neue Arbeitsbereiche beginnen mit einem kostenlosen Test von {trialDays} Tagen.</li>
      </ul>

      <H2>7. Ihre Rechte</H2>
      <p>Sie haben gegenüber dem Verantwortlichen das Recht auf:</p>
      <ul>
        <li>Auskunft (Art. 15), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung der Verarbeitung (Art. 18),</li>
        <li>Datenübertragbarkeit (Art. 20): Sie können Ihre Daten in einem gängigen Format erhalten,</li>
        <li>Widerspruch gegen Verarbeitungen auf Grundlage von Art. 6 Abs. 1 lit. f (Art. 21), insbesondere wenn sich aus Ihrer besonderen Situation Gründe ergeben,</li>
        <li>Widerruf einer erteilten Einwilligung mit Wirkung für die Zukunft (Art. 7 Abs. 3),</li>
        <li>Beschwerde bei einer Datenschutz-Aufsichtsbehörde (Art. 77){operator.supervisoryAuthority ? <>: {operator.supervisoryAuthority}</> : null}.</li>
      </ul>
      <p>
        Betreffen Ihre Anfragen Inhalte, die ein Kunde in seinem Arbeitsbereich verwaltet, leiten wir sie an diesen weiter, weil er darüber
        entscheidet; wir unterstützen ihn dabei (Art. 28 Abs. 3 lit. e DSGVO). Betreiber von Arbeitsbereichen finden unter „Datenschutzanfragen“
        Werkzeuge für Auskunft, Berichtigung und eingeschränkte Löschung.
      </p>

      <H2>8. Sicherheit</H2>
      <p>
        Wir schützen die Daten durch technische und organisatorische Maßnahmen nach Art. 32 DSGVO, unter anderem: verschlüsselte Übertragung,
        Verschlüsselung der Dokumente im Ruhezustand mit einem Schlüssel pro Arbeitsbereich, strikte Trennung der Arbeitsbereiche bis auf die
        Ebene der Datenbank, rollenbasierte Rechte, zweiter Faktor, Begrenzung der Anfragenzahl, Prüfung hochgeladener Dateien auf Schadsoftware
        und ein Änderungsprotokoll.
      </p>

      <H2>9. Änderungen</H2>
      <p>Wir passen diese Erklärung an, wenn sich der Dienst oder die Rechtslage ändert; die Version steht oben. Wesentliche Änderungen teilen wir mit.</p>
    </>
  );
}

export default function PrivacyPage() {
  return (
    <LegalShell title="Datenschutzerklärung">
      <Body />
    </LegalShell>
  );
}
