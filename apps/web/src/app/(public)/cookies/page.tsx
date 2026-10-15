'use client';

import { H2, LegalShell } from '@/legal/legal-shell';

const ROWS: readonly { readonly name: string; readonly kind: string; readonly purpose: string; readonly duration: string }[] = [
  { name: 'bc_session', kind: 'Cookie (HttpOnly, nur über HTTPS)', purpose: 'Hält Ihre Anmeldung. Ohne ihn ist keine Anmeldung möglich.', duration: 'bis zum Abmelden, spätestens nach Ablauf der Sitzung' },
  { name: 'XSRF-TOKEN', kind: 'Cookie', purpose: 'Schutz vor gefälschten Anfragen (CSRF) bei Aktionen, die etwas ändern.', duration: 'Sitzung' },
  { name: 'bc_locale', kind: 'Cookie', purpose: 'Merkt sich die gewählte Sprache (Deutsch, Englisch, Russisch), damit die Seite sofort in ihr erscheint.', duration: 'bis zu einem Jahr' },
  { name: 'bc_appearance', kind: 'Cookie', purpose: 'Merkt sich Ihre Darstellungswünsche (hell/dunkel, Kontrast, Schriftgröße, reduzierte Bewegung), damit die Seite beim Laden nicht aufblitzt.', duration: 'bis zu einem Jahr' },
  { name: 'bc.appearance', kind: 'Lokaler Speicher des Browsers', purpose: 'Dieselbe Einstellung, lokal im Browser gehalten.', duration: 'bis Sie sie löschen' },
  { name: 'bc.iris.conversation', kind: 'Sitzungsspeicher des Browsers', purpose: 'Hält den Verlauf des Hilfe-Assistenten, solange der Tab offen ist.', duration: 'bis der Tab geschlossen wird' },
];

export default function CookiesPage() {
  return (
    <LegalShell title="Cookies und Speicher im Browser">
      <p>
        Diese Seite verwendet <strong>keine Tracking-, Analyse- oder Werbe-Cookies</strong> und bindet keine Inhalte externer Anbieter
        ein (keine externen Schriften, Skripte, Karten oder Videos). Gespeichert wird nur, was für die Anmeldung, die Sicherheit und
        die von Ihnen gewählten Einstellungen nötig ist.
      </p>
      <H2>Rechtsgrundlage</H2>
      <p>
        Das Speichern und Auslesen dieser Informationen ist nach § 25 Abs. 2 Nr. 2 TDDDG ohne Einwilligung zulässig, weil es
        unbedingt erforderlich ist, um den von Ihnen ausdrücklich gewünschten Dienst (die Anmeldung und die Nutzung Ihres
        Arbeitsbereichs) bereitzustellen. Die Verarbeitung personenbezogener Daten dabei stützt sich auf Art. 6 Abs. 1 lit. b und f
        DSGVO. Weil nichts Einwilligungspflichtiges gesetzt wird, gibt es hier kein Einwilligungsbanner; würden wir künftig
        Analyse- oder Marketing-Dienste einsetzen, würden wir vorher Ihre Einwilligung einholen.
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
            </tr>
          </thead>
          <tbody>
            {ROWS.map((row) => (
              <tr key={row.name}>
                <th scope="row" style={{ textAlign: 'left', verticalAlign: 'top' }}><code>{row.name}</code></th>
                <td style={{ verticalAlign: 'top' }}>{row.kind}</td>
                <td style={{ verticalAlign: 'top' }}>{row.purpose}</td>
                <td style={{ verticalAlign: 'top' }}>{row.duration}</td>
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
