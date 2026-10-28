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
    else if (url.endsWith('/versions')) body = [{ id: 'v1', contractId: 'c1', versionLabel: 'V1', effectiveFrom: null, changeNote: 'Dokument aus Vorlage erzeugt', current: true, createdAt: '2026-10-15T08:00:00Z', documentId: 'doc-1' }];
    else if (url.includes('/templates')) body = { items: [{ id: 'tpl1', name: 'Mietvertrag', category: 'CONTRACT', status: 'PUBLISHED', updated_at: '2026-10-15T08:00:00Z' }] };
    else if (url.endsWith('/generate')) body = { versionId: 'v2', documentId: 'doc-2', generatedDocuments: { pdf: 'g1', docx: 'g2' }, usedFields: [] };
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

  it('shows the versions with a download link for the bound document', async () => {
    serve();
    renderWithProviders(<ContractPage />);
    expect(await screen.findByText('V1')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /Dokument herunterladen/ });
    expect(link).toHaveAttribute('href', '/api/v1/contracts/versions/v1/download');
  });

  it('generates the document from a chosen CONTRACT template', async () => {
    serve();
    renderWithProviders(<ContractPage />);
    await userEvent.selectOptions(await screen.findByLabelText('Vorlage'), 'tpl1');
    await userEvent.click(screen.getByRole('button', { name: /Dokument aus Vorlage erzeugen/ }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/contracts/c1/generate'))).toBe(true));
  });
});
