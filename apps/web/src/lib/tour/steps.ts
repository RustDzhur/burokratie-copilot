/** One stop of the guided tour: what to point at (a CSS selector, none = a centred card), where to be, and the texts (keys of `tour`). */
export interface TourStep {
  readonly id: string;
  readonly selector?: string;
  readonly href?: string;
}

const COMMON_BEFORE: readonly TourStep[] = [
  { id: 'welcome' },
  { id: 'attention', selector: '[data-tour="attention"]', href: '/dashboard' },
  { id: 'inbox', selector: 'nav a[href="/inbox"]' },
  { id: 'scan', selector: 'nav a[href="/scan"]' },
  { id: 'documents', selector: 'nav a[href="/documents"]' },
  { id: 'review', selector: '[data-testid="pending-review"], nav a[href="/review"]' },
  { id: 'tasks', selector: 'nav a[href="/tasks"]' },
  { id: 'contracts', selector: 'nav a[href="/contracts"]' },
];

const BUSINESS: readonly TourStep[] = [
  { id: 'invoices', selector: 'nav a[href="/invoices"]' },
  { id: 'payments', selector: 'nav a[href="/payments"]' },
];

const COMMON_AFTER: readonly TourStep[] = [
  { id: 'radar', selector: 'nav a[href="/radar"]' },
  { id: 'calendar', selector: 'nav a[href="/calendar"]' },
  { id: 'search', selector: '[data-tour="search"]' },
  { id: 'account', selector: '[data-tour="account"]' },
  { id: 'finish' },
];

/** The steps for a demo cabinet; a stop whose target is not on the screen (a menu entry the plan does not include) is left out. */
export function stepsFor(persona: string): readonly TourStep[] {
  const extra: TourStep[] = [];
  if (persona === 'FAMILY') extra.push({ id: 'parties', selector: 'nav a[href="/parties"]' });
  if (persona === 'COMPANY') {
    extra.push({ id: 'employees', selector: 'nav a[href="/employees"]' });
    extra.push({ id: 'company', selector: 'nav a[href="/company"]' });
  }
  if (persona === 'ADVISOR') extra.push({ id: 'switcher', selector: '[data-tour="switcher"]' });
  const business = persona === 'FREELANCER' || persona === 'COMPANY' || persona === 'ADVISOR' ? BUSINESS : [];
  return [...COMMON_BEFORE, ...business, ...extra, ...COMMON_AFTER];
}

/** Whether the element is on the screen (hidden menus do not count). */
export function visible(element: Element | null): element is HTMLElement {
  return element instanceof HTMLElement && element.getClientRects().length > 0;
}
