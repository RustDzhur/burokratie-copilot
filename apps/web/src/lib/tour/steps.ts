/** One stop of the guided tour: what to point at (a CSS selector, none = a centred card), where to be, and the texts (keys in the screen namespace). */
export interface TourStep {
  readonly id: string;
  readonly selector?: string;
  readonly href?: string;
}

/**
 * The tour of the Kanzlei edition (§6.1: five minutes, then work).
 *
 * <p>It walks the three questions a bookkeeper asks - what came in, whose paperwork it is, what has to leave the
 * office - and stops there. The previous tour pointed at nine menu entries this edition deleted: inbox, scan,
 * review, tasks, contracts, payments, radar and the workspace switcher, so a demo guest was shown arrows pointing
 * at nothing.</p>
 */
const STEPS: readonly TourStep[] = [
  { id: 'welcome' },
  { id: 'posteingang', selector: 'nav a[href="/documents"]' },
  { id: 'belege', selector: 'nav a[href="/invoices"]' },
  { id: 'fristen', selector: 'nav a[href="/calendar"]' },
  { id: 'mandanten', selector: 'nav a[href="/mandanten"]' },
  { id: 'exporte', selector: 'nav a[href="/exports"]' },
  { id: 'search', selector: '[data-tour="search"]' },
  { id: 'account', selector: '[data-tour="account"]' },
  { id: 'finish' },
];

/**
 * The steps for a demo cabinet.
 *
 * <p>One edition, one tour. The parameter stays because the demo entry point passes a persona and a caller that
 * sends one must keep compiling; the persona no longer changes what the product is.</p>
 */
export function stepsFor(_persona: string): readonly TourStep[] {
  return STEPS;
}

/** Whether the element is on the screen (hidden menus do not count). */
export function visible(element: Element | null): element is HTMLElement {
  return element instanceof HTMLElement && element.getClientRects().length > 0;
}
