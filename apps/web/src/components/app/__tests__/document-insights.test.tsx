import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen } from '@/test/render';
import { SessionProvider } from '@/lib/auth/session-context';
import type { SessionUser } from '@/lib/api/types';
import { DocumentInsights } from '../document-insights';

vi.mock('next/navigation', () => ({
  usePathname: () => '/documents/d1',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const user = (planTier: number): SessionUser =>
  ({
    id: 'u1',
    name: 'Erika',
    email: 'erika@example.de',
    roles: [],
    workspaceId: 'w1',
    workspaceName: 'W',
    workspaceType: 'company',
    subscription: { planTier },
  }) as unknown as SessionUser;

function serve(findings: readonly object[], cases: readonly object[]): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const body = url.includes('/radar/findings') ? { items: findings, total: findings.length } : cases;
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const finding = {
  id: 'f1',
  finding_type: 'CONFLICTING_FACTS',
  rule_id: 'E1',
  rule_version: 1,
  title: 'Betrag widerspricht',
  status: 'NEW',
  severity: 'HIGH',
  detected_at: '2026-10-15T08:00:00Z',
  last_evaluated_at: '2026-10-15T08:00:00Z',
  allowedStatuses: [],
};

describe('DocumentInsights', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lists the open findings that cite the document and the cases it belongs to, each as a link', async () => {
    const fetchMock = serve([finding], [{ id: 'c1', title: 'Widerspruch', status: 'NEW', link_origin: 'MANUAL', is_primary: false, confirmed_at: null }]);
    const { container } = renderWithProviders(
      <SessionProvider user={user(1)}>
        <DocumentInsights documentId="d1" />
      </SessionProvider>,
    );
    expect(await screen.findByRole('link', { name: 'Betrag widerspricht' })).toHaveAttribute('href', '/radar/f1');
    expect(screen.getByRole('link', { name: 'Widerspruch' })).toHaveAttribute('href', '/cases/c1');
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('documentId=d1'))).toBe(true);
    await expectNoA11yViolations(container);
  });

  it('says so when nothing is found, instead of staying silent', async () => {
    serve([], []);
    renderWithProviders(
      <SessionProvider user={user(1)}>
        <DocumentInsights documentId="d1" />
      </SessionProvider>,
    );
    expect(await screen.findByText('Keine offenen Hinweise zu diesem Dokument.')).toBeInTheDocument();
    expect(screen.getByText('Das Dokument gehört zu keinem Vorgang.')).toBeInTheDocument();
  });

  it('does not ask for findings on a plan without the Radar, but still shows the cases', async () => {
    const fetchMock = serve([finding], [{ id: 'c1', title: 'Widerspruch', status: 'NEW', link_origin: 'MANUAL', is_primary: false, confirmed_at: null }]);
    renderWithProviders(
      <SessionProvider user={user(0)}>
        <DocumentInsights documentId="d1" />
      </SessionProvider>,
    );
    expect(await screen.findByRole('link', { name: 'Widerspruch' })).toBeInTheDocument();
    expect(screen.queryByText('Betrag widerspricht')).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/radar/'))).toBe(false);
  });
});
