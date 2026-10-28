import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import RadarFindingPage from '../[id]/page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/radar/f1',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'f1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const detail = (overrides: Record<string, unknown> = {}) => ({
  id: 'f1',
  finding_type: 'INVOICE_ANOMALY',
  rule_id: 'B1',
  rule_version: 1,
  title: 'Rechnungsbetrag weicht vom letzten Betrag ab',
  description: 'Der Betrag 140.00 EUR ist 40.0 % höher als der letzte Betrag 100.00 EUR.',
  status: 'NEW',
  severity: 'MEDIUM',
  confidence: 1,
  detected_at: '2026-10-15T08:00:00Z',
  last_evaluated_at: '2026-10-15T08:00:00Z',
  reason_codes: ['AMOUNT_DIFFERS_FROM_PREVIOUS_INVOICE'],
  allowedStatuses: ['UNDER_REVIEW', 'CONFIRMED', 'DISMISSED', 'SNOOZED', 'RESOLVED'],
  evidence: [
    { id: 'e1', role: 'PRIMARY', document_id: 'doc-2', document_name: 'Rechnung-2.pdf', page: 1, snippet: 'Betrag 140,00' },
    { id: 'e2', role: 'COMPARISON', document_id: 'doc-1', document_name: 'Rechnung-1.pdf', page: null, snippet: null },
  ],
  history: [{ id: 'h1', event_type: 'DETECTED', from_status: null, to_status: 'NEW', reason: 'Detected by B1', created_at: '2026-10-15T08:00:00Z' }],
  ...overrides,
});

type Call = { url: string; method: string; body: string | null };
const calls: Call[] = [];

function serve(current: () => unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : null });
      return new Response(JSON.stringify(current()), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('Radar finding page', () => {
  beforeEach(() => {
    calls.length = 0;
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows why the finding exists, both documents it rests on, and says it is a hint', async () => {
    serve(() => detail());
    renderWithProviders(<RadarFindingPage />);
    expect(await screen.findByText(/40.0 % höher/)).toBeInTheDocument();
    expect(screen.getByText(/Hinweis, kein Urteil/)).toBeInTheDocument();
    expect(screen.getByText('Hauptbeleg')).toBeInTheDocument();
    expect(screen.getByText('Zum Vergleich')).toBeInTheDocument();
    const links = screen.getAllByRole('link', { name: 'Dokument öffnen' });
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/documents/doc-2', '/documents/doc-1']);
  });

  it('has no accessibility violations with its evidence, form and history', async () => {
    serve(() => detail());
    const { container } = renderWithProviders(<RadarFindingPage />);
    await screen.findByText(/40.0 % höher/);
    await expectNoA11yViolations(container);
  });

  it('offers only the decisions the backend allows', async () => {
    serve(() => detail({ status: 'DISMISSED', allowedStatuses: ['REOPENED'] }));
    renderWithProviders(<RadarFindingPage />);
    await screen.findByText(/40.0 % höher/);
    expect(screen.getByRole('button', { name: 'Wieder öffnen' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Bestätigen' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ablehnen' })).not.toBeInTheDocument();
  });

  it('refuses to dismiss without a reason, and sends the reason when there is one', async () => {
    serve(() => detail());
    renderWithProviders(<RadarFindingPage />);
    await screen.findByText(/40.0 % höher/);

    await userEvent.click(screen.getByRole('button', { name: 'Ablehnen' }));
    expect(await screen.findByText('Bitte eine Begründung angeben.')).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0);

    await userEvent.type(screen.getByLabelText(/Begründung/), 'Preiserhöhung war vereinbart');
    await userEvent.click(screen.getByRole('button', { name: 'Ablehnen' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'POST')).toBe(true));
    const post = calls.find((call) => call.method === 'POST');
    expect(post?.url).toContain('/radar/findings/f1/dismiss');
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ reason: 'Preiserhöhung war vereinbart' });
  });
});
