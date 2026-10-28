/** The legal pages every public and sign-in screen links to (imprint and privacy notice must be reachable from everywhere). */
export const LEGAL_LINKS: readonly { readonly href: string; readonly label: string }[] = [
  { href: '/impressum', label: 'Impressum' },
  { href: '/datenschutz', label: 'Datenschutzerklärung' },
  { href: '/agb', label: 'AGB' },
  { href: '/widerruf', label: 'Widerrufsbelehrung' },
  { href: '/avv', label: 'Auftragsverarbeitung (AVV)' },
  { href: '/cookies', label: 'Cookies und Speicher' },
  { href: '/barrierefreiheit', label: 'Barrierefreiheit' },
];
