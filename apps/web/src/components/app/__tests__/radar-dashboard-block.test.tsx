import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen } from '@/test/render';
import { SessionProvider } from '@/lib/auth/session-context';
import type { SessionUser } from '@/lib/api/types';
import { RadarDashboardBlock } from '../radar-dashboard-block';

vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
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

function serve(): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const body = url.includes('/radar/summary')
      ? {
          byStatus: { NEW: 2, CONFIRMED: 1 },
          openBySeverity: { HIGH: 1, MEDIUM: 2 },
          recentlyResolved: [{ id: 'r1', title: 'Doppelte Rechnung', severity: 'MEDIUM', resolved_at: '2026-10-14T08:00:00Z' }],
        }
      : {
          items: [
            {
              id: 'f1',
              finding_type: 'INVOICE_ANOMALY',
              rule_id: 'B1',
              rule_version: 1,
              title: 'Rechnungsbetrag weicht vom letzten Betrag ab',
              status: 'NEW',
              severity: 'HIGH',
              detected_at: '2026-10-15T08:00:00Z',
              last_evaluated_at: '2026-10-15T08:00:00Z',
              allowedStatuses: [],
            },
          ],
          total: 1,
        };
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('RadarDashboardBlock', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows the waiting and confirmed counts and the newest finding, each opening its page', async () => {
    serve();
    const { container } = renderWithProviders(
      <SessionProvider user={user(1)}>
        <RadarDashboardBlock />
      </SessionProvider>,
    );
    expect(await screen.findByTestId('radar-counts')).toHaveTextContent('2 Befunde warten auf Prüfung, 1 bestätigt.');
    expect(screen.getByRole('link', { name: 'Rechnungsbetrag weicht vom letzten Betrag ab' })).toHaveAttribute('href', '/radar/f1');
    expect(screen.getByRole('link', { name: 'Doppelte Rechnung' })).toHaveAttribute('href', '/radar/r1');
    expect(screen.getByRole('link', { name: 'Alle Befunde ansehen' })).toHaveAttribute('href', '/radar');
    await expectNoA11yViolations(container);
  });

  it('does not ask the server and shows nothing on a plan without the Radar', () => {
    const fetchMock = serve();
    const { container } = renderWithProviders(
      <SessionProvider user={user(0)}>
        <RadarDashboardBlock />
      </SessionProvider>,
    );
    expect(container).toBeEmptyDOMElement();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
