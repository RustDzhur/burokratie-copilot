'use client';

import { H2, LegalShell, Op, useLegal } from '@/legal/legal-shell';

function Body() {
  const { operator } = useLegal();
  return (
    <>
      <p>Dieses Widerrufsrecht gilt nur für Verbraucher, also für natürliche Personen, die den Vertrag zu Zwecken abschließen, die überwiegend weder ihrer gewerblichen noch ihrer selbständigen beruflichen Tätigkeit zugerechnet werden können.</p>
      <H2>Widerrufsbelehrung</H2>
      <h3>Widerrufsrecht</h3>
      <p>
        Sie haben das Recht, binnen vierzehn Tagen ohne Angabe von Gründen diesen Vertrag zu widerrufen. Die Widerrufsfrist beträgt vierzehn
        Tage ab dem Tag des Vertragsabschlusses.
      </p>
      <p>
        Um Ihr Widerrufsrecht auszuüben, müssen Sie uns (<Op field="name" />, <span style={{ whiteSpace: 'pre-line' }}><Op field="address" /></span>,
        E-Mail: <Op field="email" />
        {operator.phone ? <>, Telefon: {operator.phone}</> : null}) mittels einer eindeutigen Erklärung (z. B. ein mit der Post versandter
        Brief oder eine E-Mail) über Ihren Entschluss, diesen Vertrag zu widerrufen, informieren. Sie können dafür das beigefügte
        Muster-Widerrufsformular verwenden, das jedoch nicht vorgeschrieben ist.
      </p>
      <p>Zur Wahrung der Widerrufsfrist reicht es aus, dass Sie die Mitteilung über die Ausübung des Widerrufsrechts vor Ablauf der Widerrufsfrist absenden.</p>
      <h3>Folgen des Widerrufs</h3>
      <p>
        Wenn Sie diesen Vertrag widerrufen, haben wir Ihnen alle Zahlungen, die wir von Ihnen erhalten haben, unverzüglich und spätestens
        binnen vierzehn Tagen ab dem Tag zurückzuzahlen, an dem die Mitteilung über Ihren Widerruf dieses Vertrags bei uns eingegangen ist.
        Für diese Rückzahlung verwenden wir dasselbe Zahlungsmittel, das Sie bei der ursprünglichen Transaktion eingesetzt haben, es sei
        denn, mit Ihnen wurde ausdrücklich etwas anderes vereinbart; in keinem Fall werden Ihnen wegen dieser Rückzahlung Entgelte berechnet.
      </p>
      <p>
        Haben Sie verlangt, dass die Dienstleistung während der Widerrufsfrist beginnen soll, so haben Sie uns einen angemessenen Betrag zu
        zahlen, der dem Anteil der bis zu dem Zeitpunkt, zu dem Sie uns von der Ausübung des Widerrufsrechts hinsichtlich dieses Vertrags
        unterrichten, bereits erbrachten Dienstleistungen im Vergleich zum Gesamtumfang der im Vertrag vorgesehenen Dienstleistungen entspricht.
      </p>
      <h3>Vorzeitiges Erlöschen des Widerrufsrechts</h3>
      <p>
        Das Widerrufsrecht erlischt bei einem Vertrag über die Erbringung von Dienstleistungen, wenn wir die Dienstleistung vollständig
        erbracht haben und mit der Ausführung erst begonnen haben, nachdem Sie dazu Ihre ausdrückliche Zustimmung gegeben und gleichzeitig
        bestätigt haben, dass Sie Ihr Widerrufsrecht bei vollständiger Vertragserfüllung durch uns verlieren.
      </p>

      <H2>Muster-Widerrufsformular</H2>
      <p>(Wenn Sie den Vertrag widerrufen wollen, füllen Sie bitte dieses Formular aus und senden Sie es zurück.)</p>
      <blockquote style={{ borderLeft: '3px solid currentColor', paddingLeft: '1rem', margin: '1rem 0' }}>
        <p>
          An <Op field="name" />, <span style={{ whiteSpace: 'pre-line' }}><Op field="address" /></span>, E-Mail: <Op field="email" />:
        </p>
        <p>Hiermit widerrufe(n) ich/wir (*) den von mir/uns (*) abgeschlossenen Vertrag über die Erbringung der folgenden Dienstleistung (*):</p>
        <p>Bestellt am (*) / erhalten am (*):</p>
        <p>Name des/der Verbraucher(s):</p>
        <p>Anschrift des/der Verbraucher(s):</p>
        <p>Unterschrift des/der Verbraucher(s) (nur bei Mitteilung auf Papier):</p>
        <p>Datum:</p>
        <p><small>(*) Unzutreffendes streichen.</small></p>
      </blockquote>
    </>
  );
}

export default function WithdrawalPage() {
  return (
    <LegalShell title="Widerrufsbelehrung und Muster-Widerrufsformular">
      <Body />
    </LegalShell>
  );
}
