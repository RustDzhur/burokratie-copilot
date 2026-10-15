import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import ContractPage from '../[id]/page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/contracts/c1',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'c1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const calls: { url: string; method: string }[] = [];
function serve(): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? 'GET' });
    let body: unknown = {};
    if (url.endsWith('/contracts/c1')) body = { contract: { id: 'c1', title: 'Strom', status: 'ACTIVE', partnerName: 'Stadtwerke', costAmount: 100, costCurrency: 'EUR', costInterval: 'MONTHLY' } };
    else if (url.endsWith('/obligations')) body = [{ id: 'o1', title: 'Kündigungsfrist beachten', dueDate: '2026-12-01', status: 'OPEN' }];
    else if (url.includes('/relationships')) body = [{ id: 'm1', source: 'MATCH', type: 'CONTRACT', status: 'SUGGESTED', document_id: 'd1', document_name: 'Vertrag.pdf', evidence: { rule: 'contract number' } }];
    else if (url.endsWith('/timeline')) body = [{ source: 'CONTRACT', source_event_id: 'e1', event_type: 'CREATED', title: 'CREATED', event_time: '2026-10-15T08:00:00Z', recorded_at: '2026-10-15T08:00:00Z' }];
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
}

describe('Contract page', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows terms, obligations, linked documents and the timeline', async () => {
    serve();
    const { container } = renderWithProviders(<ContractPage />);
    expect(await screen.findByText(/Kündigungsfrist beachten/)).toBeInTheDocument();
    expect(screen.getByText(/100 EUR MONTHLY/)).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Vertrag.pdf' })).toHaveAttribute('href', '/documents/d1');
    await expectNoA11yViolations(container);
  });

  it('lets a person decide a suggested link', async () => {
    serve();
    renderWithProviders(<ContractPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Bestätigen' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.includes('/memory/relationships/m1/confirm'))).toBe(true));
  });
});
