import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import ContactProfilePage from '../[id]/page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/contacts/k1',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'k1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const profile = {
  contact: { id: 'k1', display_name: 'Stadtwerke Musterstadt', email: 'info@sw.example', phone: null, is_customer: false, is_supplier: true },
  identifiers: [{ id: 'i1', kind: 'IBAN', value: 'DE89370400440532013000' }],
  documents: [
    { relationship_id: 'r1', document_id: 'd1', name: 'Rechnung.pdf', status: 'SUGGESTED', score: 0.8 },
    { relationship_id: 'r2', document_id: 'd2', name: 'Vertrag.pdf', status: 'CONFIRMED', score: 1 },
  ],
  contracts: [{ id: 'c1', title: 'Strom', status: 'ACTIVE', notice_deadline_date: null }],
  invoices: [{ id: 'v1', invoice_number: 'R-1', gross_amount: '96.00', currency: 'EUR', status: 'RECEIVED' }],
  cases: [{ id: 'f1', reference: 'K-1', title: 'Preiserhöhung', status: 'NEW' }],
  openFindings: [],
};
const timeline = [{ source: 'CASE', source_event_id: 'e1', event_type: 'CASE_CREATED', title: 'Vorgang angelegt', event_time: '2026-10-15T08:00:00Z', recorded_at: '2026-10-15T08:00:00Z' }];

const calls: { url: string; method: string }[] = [];
function serve(): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? 'GET' });
    const body = url.endsWith('/timeline') ? timeline : url.includes('/memory/entities/') ? profile : {};
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
}

describe('Contact profile', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows identifiers, documents, contracts, invoices, cases and the timeline', async () => {
    serve();
    const { container } = renderWithProviders(<ContactProfilePage />);
    expect(await screen.findByText('DE89370400440532013000')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Rechnung.pdf' })).toHaveAttribute('href', '/documents/d1');
    expect(screen.getByText(/Strom · ACTIVE/)).toBeInTheDocument();
    expect(screen.getByText(/R-1 · 96.00 EUR/)).toBeInTheDocument();
    expect(await screen.findByText(/Vorgang angelegt/)).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('offers confirm and reject only for a suggested link, and sends the decision', async () => {
    serve();
    renderWithProviders(<ContactProfilePage />);
    await screen.findByText('Rechnung.pdf');
    expect(screen.getAllByRole('button', { name: 'Bestätigen' })).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Ablehnen' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/memory/relationships/r1/reject'))).toBe(true));
  });
});
