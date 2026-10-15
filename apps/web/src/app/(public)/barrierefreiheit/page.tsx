'use client';

import Link from 'next/link';
import { H2, LegalShell, Op } from '@/legal/legal-shell';

export default function AccessibilityStatementPage() {
  return (
    <LegalShell title="Erklärung zur Barrierefreiheit">
      <p>
        Wir möchten, dass jeder diese Anwendung nutzen kann. Diese Erklärung beschreibt den Stand der Barrierefreiheit; sie bezieht sich auf
        die Webanwendung unter dieser Adresse, auf die Anforderungen des Barrierefreiheitsstärkungsgesetzes (BFSG) und die Norm EN 301 549
        (WCAG 2.1, Stufe AA).
      </p>
      <H2>Stand der Vereinbarkeit</H2>
      <p>
        Die Anwendung ist <strong>teilweise</strong> mit den Anforderungen vereinbar. Wir haben sie von Beginn an mit Blick auf Barrierefreiheit
        gebaut und prüfen sie laufend automatisch; eine vollständige Prüfung durch Menschen mit Hilfsmitteln steht noch aus.
      </p>
      <H2>Was umgesetzt und geprüft ist</H2>
      <ul>
        <li>Automatische Prüfung der Seiten auf Barrieren (axe) bei Änderungen; geprüfte Seiten: Startseite, Anmeldung, Übersicht, Eingang und weitere.</li>
        <li>Farbkontraste der Oberfläche sind automatisch geprüft (hell und dunkel).</li>
        <li>Alle Eingabefelder haben Beschriftungen; Fehlermeldungen werden vorgelesen; Dialoge lassen sich mit der Escape-Taste schließen.</li>
        <li>Ein „Zum Inhalt springen“-Link steht am Anfang jeder Seite.</li>
        <li>Darstellung einstellbar: hell/dunkel, hoher Kontrast, größere Schrift, reduzierte Bewegung (<Link href="/accessibility">Einstellungen</Link>).</li>
        <li>Die Oberfläche ist für kleine Bildschirme ausgelegt.</li>
      </ul>
      <H2>Bekannte Einschränkungen</H2>
      <ul>
        <li>Die Bedienung nur mit der Tastatur ist noch nicht vollständig von Menschen geprüft (Reihenfolge, sichtbarer Fokus, Rückkehr des Fokus nach Dialogen).</li>
        <li>Eine Prüfung mit gängigen Bildschirmlesern (z. B. NVDA, VoiceOver) ist noch nicht abgeschlossen.</li>
        <li>Dokumente, die Nutzer hochladen (Scans, Fotos, PDF), sind nicht von uns gestaltet und können selbst nicht barrierefrei sein.</li>
      </ul>
      <H2>Rückmeldung und Kontakt</H2>
      <p>
        Haben Sie eine Barriere bemerkt oder brauchen Sie Inhalte in anderer Form? Schreiben Sie uns: <Op field="email" />.
      </p>
      <H2>Durchsetzungsverfahren</H2>
      <p>
        Wenn Sie mit unserer Antwort nicht zufrieden sind, können Sie sich an die zuständige Marktüberwachungsbehörde der Länder wenden
        (Marktüberwachungsstelle der Länder für die Barrierefreiheit von Produkten und Dienstleistungen, MLBF) oder die Schlichtungsstelle
        nach dem BFSG anrufen.
      </p>
      <p><small>Diese Erklärung wurde nach dem Stand der Anwendung zur angegebenen Version erstellt und wird bei Änderungen aktualisiert.</small></p>
    </LegalShell>
  );
}
