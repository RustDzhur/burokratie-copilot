import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  expectNoA11yViolations,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  within,
} from '@/test/render';
import { formatDate } from '@/i18n/format';
import type { MandantSummary } from '@/lib/api/types';
import MandantenPage from '../page';

/**
 * The client list of the Kanzlei (Kanzlei edition, §5.5).
 *
 * <p>Checked are the three things a bookkeeper depends on: the state is readable without opening the row,
 * the problem switch actually asks the server instead of filtering a page in the browser, and a cabinet
 * without clients gets the sentence of §6.2 rather than an empty table.</p>
 */

const calls: string[] = [];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const okClient: MandantSummary = {
  id: 'm-1',
  displayName: 'Alpha GmbH',
  contactType: 'organisation',
  email: 'alpha@example.de',
  city: 'Berlin',
  documentsTotal: 4,
  offen: 1,
  zuPruefen: 1,
  exportiert: 1,
  fehler: 0,
  deadlinesOpen: 2,
  lastActivityAt: '2026-03-05',
  health: 'ACTION',
};

const calmClient: MandantSummary = {
  id: 'm-3',
  displayName: 'Gamma KG',
  contactType: 'organisation',
  email: null,
  city: null,
  documentsTotal: 2,
  offen: 0,
  zuPruefen: 0,
  exportiert: 2,
  fehler: 0,
  deadlinesOpen: 0,
  lastActivityAt: '2026-03-09',
  health: 'OK',
};

const riskClient: MandantSummary = {
  id: 'm-2',
  displayName: 'Beta AG',
  contactType: 'organisation',
  email: null,
  city: null,
  documentsTotal: 3,
  offen: 0,
  zuPruefen: 0,
  exportiert: 2,
  fehler: 1,
  deadlinesOpen: 0,
  lastActivityAt: '2026-02-01',
  health: 'RISK',
};

function serve(rows: readonly MandantSummary[]): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      calls.push(url);
      if (url.includes('/auth/csrf')) return json({ token: 'test' });
      if (url.includes('/mandanten')) {
        // The switch is a server-side filter: the answer names only the clients that need attention.
        const onlyProblems = url.includes('onlyProblems=true');
        return json(onlyProblems ? rows.filter((row) => row.health !== 'OK') : rows);
      }
      return json({});
    }),
  );
}

describe('Mandanten list', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('renders every client with its status and its counters', async () => {
    serve([okClient, riskClient, calmClient]);
    const { container } = renderWithProviders(<MandantenPage />);

    expect(await screen.findByRole('link', { name: 'Alpha GmbH' })).toHaveAttribute(
      'href',
      '/mandanten/m-1',
    );

    const actionRow = screen.getByRole('row', { name: /Alpha GmbH/ });
    expect(within(actionRow).getByText('Handlungsbedarf')).toBeInTheDocument();
    // Belege, Offen, Zu prüfen, Fristen - the four numbers the office reads without opening the client.
    expect(within(actionRow).getByText('4')).toBeInTheDocument();
    expect(within(actionRow).getByText('2')).toBeInTheDocument();
    expect(within(actionRow).getByText(formatDate('2026-03-05', 'de'))).toBeInTheDocument();

    const riskRow = screen.getByRole('row', { name: /Beta AG/ });
    expect(within(riskRow).getByText('Risiko')).toBeInTheDocument();

    // One request for the whole list, not one per client.
    expect(calls.filter((url) => url.includes('/mandanten'))).toHaveLength(1);
    await expectNoA11yViolations(container);
  });

  it('keeps only the problem cases when the switch is on, and asks the server for them', async () => {
    serve([okClient, riskClient, calmClient]);
    renderWithProviders(<MandantenPage />);

    expect(await screen.findByRole('link', { name: 'Gamma KG' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('switch', { name: /Nur Problemfälle/ }));

    await waitFor(() =>
      expect(calls.some((url) => url.includes('/mandanten') && url.includes('onlyProblems=true'))).toBe(
        true,
      ),
    );
    // The healthy client disappears; the two that need a person stay - one waits, one is at risk.
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Gamma KG' })).toBeNull());
    expect(screen.getByRole('link', { name: 'Alpha GmbH' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Beta AG' })).toBeInTheDocument();
  });

  it('offers the first client instead of an empty table in a fresh cabinet', async () => {
    serve([]);
    renderWithProviders(<MandantenPage />);

    expect(await screen.findByText('Noch kein Mandant')).toBeInTheDocument();
    expect(screen.getByText(/Legen Sie Ihren ersten Mandanten an/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Mandant anlegen' })).toHaveAttribute(
      'href',
      '/contacts',
    );
  });
});
