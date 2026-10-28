import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import ApprovalsPage from '../page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/approvals',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const payment = {
  id: 'a1',
  title: 'Zahlung über 1190.00 EUR',
  justification: 'Rechnung geprüft',
  subject_type: 'PAYMENT',
  approval_type: 'PAYMENT_APPROVAL',
  required_role: null,
  requested_by_name: 'Erika Mustermann',
  expires_at: '2026-10-20T08:00:00Z',
  created_at: '2026-10-15T08:00:00Z',
  bound: true,
  case: { id: 'c1', reference: 'K-1', title: 'Preiserhöhung' },
  material: { amount: '1190.00', currency: 'EUR', counterparty_name: 'Stadtwerke', counterparty_iban: 'DE89370400440532013000', reference: 'RE-1' },
  warnings: ['BANK_DETAILS_CHANGED'],
  mayDecide: true,
};

type Call = { url: string; method: string; body: string | null };
const calls: Call[] = [];

function serve(items: unknown[]): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : null });
      const body = String(input).includes('/decision') ? { status: 'APPROVED' } : items;
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('Approvals page', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows the material, the case, the warning and the binding, without accessibility violations', async () => {
    serve([payment]);
    const { container } = renderWithProviders(<ApprovalsPage />);
    expect(await screen.findByText('Zahlung über 1190.00 EUR')).toBeInTheDocument();
    expect(screen.getByText('1190.00 EUR')).toBeInTheDocument();
    expect(screen.getByText('DE89370400440532013000')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /K-1/ })).toHaveAttribute('href', '/cases/c1');
    expect(screen.getByText(/Kontoverbindung weicht von früheren Zahlungen/)).toBeInTheDocument();
    expect(screen.getByText(/gilt nur für dieses Material/)).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('refuses to reject or to ask for changes without a note, and sends the decision with it', async () => {
    serve([payment]);
    renderWithProviders(<ApprovalsPage />);
    await screen.findByText('Zahlung über 1190.00 EUR');

    await userEvent.click(screen.getByRole('button', { name: 'Ablehnen' }));
    expect(await screen.findByText('Bitte eine Anmerkung angeben.')).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0);

    await userEvent.type(screen.getByLabelText(/Anmerkung/), 'Konto stimmt nicht');
    await userEvent.click(screen.getByRole('button', { name: 'Änderung erbitten' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'POST')).toBe(true));
    const post = calls.find((call) => call.method === 'POST');
    expect(post?.url).toContain('/approvals/a1/decision');
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ approved: false, changesRequested: true, note: 'Konto stimmt nicht' });
  });

  it('does not offer a decision the person is not entitled to', async () => {
    serve([{ ...payment, mayDecide: false }]);
    renderWithProviders(<ApprovalsPage />);
    await screen.findByText('Zahlung über 1190.00 EUR');
    expect(screen.queryByRole('button', { name: 'Freigeben' })).not.toBeInTheDocument();
    expect(screen.getByText(/Sie können diese Anfrage nicht entscheiden/)).toBeInTheDocument();
  });

  it('says so when nothing waits', async () => {
    serve([]);
    renderWithProviders(<ApprovalsPage />);
    expect(await screen.findByText('Keine offenen Freigaben')).toBeInTheDocument();
  });
});
