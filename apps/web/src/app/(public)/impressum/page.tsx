'use client';

import { H2, LegalShell, Op, useHas, useLegal } from '@/legal/legal-shell';

function Body() {
  const { operator } = useLegal();
  const hasPhone = useHas('phone');
  const hasForm = useHas('contactForm');
  const registered = useHas('registerNumber');
  const vat = useHas('vatId');
  return (
    <>
      <H2>Angaben gemäß § 5 DDG</H2>
      <p>
        <Op field="name" />
        {operator.legalForm ? <> ({operator.legalForm})</> : null}
        <br />
        <span style={{ whiteSpace: 'pre-line' }}><Op field="address" /></span>
      </p>
      {operator.representative ? <p>Vertreten durch: {operator.representative}</p> : null}

      <H2>Kontakt</H2>
      <p>
        E-Mail: <Op field="email" />
        {hasPhone ? (<><br />Telefon: <Op field="phone" /></>) : null}
        {hasForm ? (<><br />Kontaktformular: <Op field="contactForm" /></>) : null}
      </p>

      {registered ? (
        <>
          <H2>Registereintrag</H2>
          <p>
            {operator.register ? <>{operator.register}, </> : null}
            Registernummer: <Op field="registerNumber" />
          </p>
        </>
      ) : null}
      {vat ? (
        <>
          <H2>Umsatzsteuer-Identifikationsnummer</H2>
          <p>Umsatzsteuer-Identifikationsnummer gemäß § 27a Umsatzsteuergesetz: <Op field="vatId" /></p>
        </>
      ) : null}

      <H2>Verbraucherstreitbeilegung</H2>
      {operator.disputeResolution === 'WILLING' ? (
        <p>Wir sind bereit, an Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teilzunehmen.</p>
      ) : operator.disputeResolution === 'NOT_WILLING' ? (
        <p>Wir sind nicht bereit und nicht verpflichtet, an Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teilzunehmen.</p>
      ) : (
        <p><Op field="disputeResolution" /></p>
      )}

      <H2>Haftung für Inhalte und Links</H2>
      <p>
        Als Diensteanbieter sind wir für eigene Inhalte auf diesen Seiten nach den allgemeinen Gesetzen verantwortlich. Die
        Dokumente, Nachrichten und Daten, die Nutzer in ihren Arbeitsbereichen ablegen, sind Inhalte der jeweiligen Nutzer; wir
        machen sie uns nicht zu eigen. Auf Hinweise auf rechtswidrige Inhalte reagieren wir unverzüglich.
      </p>
    </>
  );
}

export default function ImpressumPage() {
  return (
    <LegalShell title="Impressum">
      <Body />
    </LegalShell>
  );
}
