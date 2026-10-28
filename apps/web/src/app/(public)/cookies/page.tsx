'use client';

import { H2, LegalShell } from '@/legal/legal-shell';

const ROWS: readonly { readonly name: string; readonly kind: string; readonly purpose: string; readonly duration: string; readonly basis: string }[] = [
  { name: 'bc_consent', kind: 'Cookie', purpose: 'Merkt sich Ihre Auswahl zu Cookies, damit wir nicht bei jedem Besuch erneut fragen.', duration: 'bis zu sechs Monate', basis: 'notwendig' },
  { name: 'bc_session', basis: 'notwendig', kind: 'Cookie (HttpOnly, nur über HTTPS)', purpose: 'Hält Ihre Anmeldung. Ohne ihn ist keine Anmeldung möglich.', duration: 'bis zum Abmelden, spätestens nach Ablauf der Sitzung' },
  { name: 'XSRF-TOKEN', basis: 'notwendig', kind: 'Cookie', purpose: 'Schutz vor gefälschten Anfragen (CSRF) bei Aktionen, die etwas ändern.', duration: 'Sitzung' },
  { name: 'bc_locale', basis: 'Einwilligung', kind: 'Cookie', purpose: 'Merkt sich die gewählte Sprache (Deutsch, Englisch, Russisch), damit die Seite sofort in ihr erscheint.', duration: 'bis zu einem Jahr' },
  { name: 'bc_appearance', basis: 'Einwilligung', kind: 'Cookie', purpose: 'Merkt sich Ihre Darstellungswünsche (hell/dunkel, Kontrast, Schriftgröße, reduzierte Bewegung), damit die Seite beim Laden nicht aufblitzt.', duration: 'bis zu einem Jahr' },
  { name: 'bc.appearance', basis: 'Einwilligung', kind: 'Lokaler Speicher des Browsers', purpose: 'Dieselbe Einstellung, lokal im Browser gehalten.', duration: 'bis Sie sie löschen' },
  { name: 'bc.iris.conversation', basis: 'Einwilligung', kind: 'Sitzungsspeicher des Browsers', purpose: 'Hält den Verlauf des Hilfe-Assistenten, solange der Tab offen ist.', duration: 'bis der Tab geschlossen wird' },
];

export default function CookiesPage() {
  return (
    <LegalShell title="Cookies und Speicher im Browser">
      <p>
        Diese Seite verwendet <strong>keine Tracking-, Analyse- oder Werbe-Cookies</strong> und bindet keine Inhalte externer Anbieter
        ein (keine externen Schriften, Skripte, Karten oder Videos). Notwendig gespeichert wird nur, was für die Anmeldung und die
        Sicherheit gebraucht wird. Komfort-Einträge (Sprache, Darstellung, Verlauf des Hilfe-Assistenten) legen wir nur mit Ihrer
        Einwilligung an. Ihre Auswahl können Sie jederzeit über „Cookie-Einstellungen“ im Fußbereich ändern.
      </p>
      <H2>Rechtsgrundlage</H2>
      <p>
        <strong>Notwendige</strong> Einträge (Anmeldung, Schutz vor gefälschten Anfragen, Ihre Cookie-Auswahl) sind nach § 25 Abs. 2 Nr. 2
        TDDDG ohne Einwilligung zulässig, weil sie unbedingt erforderlich sind, um den von Ihnen ausdrücklich gewünschten Dienst
        bereitzustellen; die Verarbeitung personenbezogener Daten dabei stützt sich auf Art. 6 Abs. 1 lit. b und f DSGVO.
        <strong> Komfort-Einträge</strong> speichern wir nach § 25 Abs. 1 TDDDG nur mit Ihrer Einwilligung (Art. 6 Abs. 1 lit. a DSGVO).
        Sie können die Einwilligung jederzeit mit Wirkung für die Zukunft widerrufen; die Seite funktioniert auch ohne sie, beginnt dann
        aber bei jedem Besuch mit den Standardeinstellungen. Würden wir künftig Analyse- oder Marketing-Dienste einsetzen, würden wir
        vorher gesondert Ihre Einwilligung einholen.
      </p>
      <H2>Was gespeichert wird</H2>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <caption style={{ textAlign: 'left' }}>Alle Einträge, die diese Anwendung in Ihrem Browser anlegt</caption>
          <thead>
            <tr>
              <th scope="col" style={{ textAlign: 'left' }}>Name</th>
              <th scope="col" style={{ textAlign: 'left' }}>Art</th>
              <th scope="col" style={{ textAlign: 'left' }}>Zweck</th>
              <th scope="col" style={{ textAlign: 'left' }}>Dauer</th>
              <th scope="col" style={{ textAlign: 'left' }}>Grundlage</th>
            </tr>
          </thead>
          <tbody>
            {ROWS.map((row) => (
              <tr key={row.name}>
                <th scope="row" style={{ textAlign: 'left', verticalAlign: 'top' }}><code>{row.name}</code></th>
                <td style={{ verticalAlign: 'top' }}>{row.kind}</td>
                <td style={{ verticalAlign: 'top' }}>{row.purpose}</td>
                <td style={{ verticalAlign: 'top' }}>{row.duration}</td>
                <td style={{ verticalAlign: 'top' }}>{row.basis}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <H2>Löschen</H2>
      <p>
        Sie können alle diese Einträge jederzeit in den Einstellungen Ihres Browsers löschen. Danach müssen Sie sich neu anmelden
        und Ihre Darstellungswünsche neu wählen.
      </p>
    </LegalShell>
  );
}
