import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import FuturePage from '../page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/future',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const timeline = {
  horizon: 30,
  from: '2026-10-15',
  to: '2026-11-14',
  events: [
    {
      id: 'DEADLINE:d1',
      kind: 'DEADLINE',
      date: '2026-10-25',
      title: 'Widerspruch prüfen',
      amount: null,
      currency: null,
      certainty: 'UNCONFIRMED',
      unknown: ['Frist nicht bestätigt: bitte prüfen'],
      source: { type: 'TASK_DEADLINE', id: 'd1' },
      documentId: 'doc-1',
      caseId: null,
      contractId: null,
    },
    {
      id: 'CONTRACT_END:c1',
      kind: 'CONTRACT_END',
      date: '2026-11-10',
      title: 'Vertragsende: Internet',
      amount: null,
      currency: null,
      certainty: 'RECORDED',
      unknown: ['Der Vertrag verlängert sich, die Kündigungsfrist ist nicht erfasst'],
      source: { type: 'CONTRACT', id: 'c1' },
      documentId: null,
      caseId: null,
      contractId: 'c1',
    },
  ],
  undated: [
    {
      id: 'EXPECTED_REPLY:k1',
      kind: 'EXPECTED_REPLY',
      date: null,
      title: 'Antwort des Anbieters',
      amount: null,
      currency: null,
      certainty: 'UNKNOWN_DATE',
      unknown: ['Wann die Antwort kommt, ist nicht bekannt'],
      source: { type: 'CASE_CRITERION', id: 'k1' },
      documentId: null,
      caseId: 'case-1',
      contractId: null,
    },
  ],
};

const urls: string[] = [];

function serve(scenario?: unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      urls.push(url);
      const body = url.includes('/memory/future/scenario')
        ? scenario
        : url.includes('/memory/future')
          ? timeline
          : { items: [], total: 0, page: 0, size: 100 };
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('FuturePage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    urls.length = 0;
  });

  it('shows every event with its date, marks what is unconfirmed or unknown in words, and lists the waiting reply without a date', async () => {
    serve();
    const { container } = renderWithProviders(<FuturePage />);

    expect(await screen.findByRole('link', { name: 'Widerspruch prüfen' })).toHaveAttribute('href', '/documents/doc-1');
    expect(screen.getByText('Nicht bestätigt')).toBeInTheDocument();
    expect(screen.getByText(/Nicht bekannt: Frist nicht bestätigt/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Vertragsende: Internet' })).toHaveAttribute('href', '/contracts/c1');
    expect(screen.getByText(/Kündigungsfrist ist nicht erfasst/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Wartet, Datum nicht bekannt' })).toBeInTheDocument();
    expect(screen.getByText('Kein Datum bekannt')).toBeInTheDocument();
    expect(screen.getByText(/Wann die Antwort kommt, ist nicht bekannt/)).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('asks for the chosen period and filters by kind', async () => {
    const user = userEvent.setup();
    serve();
    renderWithProviders(<FuturePage />);
    await screen.findByRole('link', { name: 'Widerspruch prüfen' });

    await user.click(screen.getByRole('button', { name: '90 Tage' }));
    await waitFor(() => expect(urls.some((url) => url.includes('/memory/future?horizon=90'))).toBe(true));

    await user.selectOptions(screen.getByLabelText('Art'), 'CONTRACT_END');
    expect(screen.queryByRole('link', { name: 'Widerspruch prüfen' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Vertragsende: Internet' })).toBeInTheDocument();
  });

  it('shows what is missing instead of a number when a scenario cannot be calculated', async () => {
    const user = userEvent.setup();
    serve({ type: 'NOTICE_MISSED', status: 'INSUFFICIENT_DATA', readOnly: true, missing: ['Kündigungsfrist', 'Vertragsende'] });
    renderWithProviders(<FuturePage />);
    await screen.findByRole('link', { name: 'Widerspruch prüfen' });

    expect(screen.getByText(/Es wird nichts verändert/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Berechnen' }));

    expect(await screen.findByText('Nicht berechnet, weil das fehlt:')).toBeInTheDocument();
    expect(screen.getByText('Kündigungsfrist', { selector: 'li' })).toBeInTheDocument();
    expect(screen.queryByText('In diesem Szenario')).not.toBeInTheDocument();
  });
});
