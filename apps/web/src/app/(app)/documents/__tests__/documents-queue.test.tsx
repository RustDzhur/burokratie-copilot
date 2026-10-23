import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  expectNoA11yViolations,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  within,
} from '@/test/render';
import DocumentsPage from '../page';

/**
 * The Posteingang as a work queue (Kanzlei edition, §5.1).
 *
 * <p>Checked here are the three promises of the screen: a filter is a request and a shareable address, a
 * selection produces the office's actions and not a decorative checkbox, and the answer of a bulk action
 * names the vouchers it did not move instead of reporting a clean success.</p>
 */

interface Call {
  readonly url: string;
  readonly method: string;
  readonly body: string | null;
}

const calls: Call[] = [];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const documents = [
  {
    id: 'd1',
    originalFilename: 'Rechnung Januar',
    processingStatus: 'READY',
    belegStatus: 'NEU',
    mandantId: 'm-1',
    reviewStatus: 'NOT_REQUIRED',
    source: 'UPLOAD',
    receivedAt: '2026-03-01T09:00:00Z',
    documentTypeCode: null,
    documentTypeNames: null,
  },
  {
    id: 'd2',
    originalFilename: 'Rechnung Februar',
    processingStatus: 'READY',
    belegStatus: 'EXPORTIERT',
    mandantId: 'm-1',
    reviewStatus: 'NOT_REQUIRED',
    source: 'UPLOAD',
    receivedAt: '2026-03-02T09:00:00Z',
    documentTypeCode: null,
    documentTypeNames: null,
  },
];

const counts = { neu: 1, offen: 0, zuPruefen: 0, exportiert: 1, fehler: 0, ohneMandant: 0 };

/** A backend that behaves like the two endpoints this screen depends on. */
function serve(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      const body = typeof init?.body === 'string' ? init.body : null;
      calls.push({ url, method, body });

      if (url.includes('/auth/csrf')) return json({ token: 'test' });
      if (url.includes('/contacts')) {
        return json({
          items: [
            {
              id: 'm-1',
              display_name: 'Alpha GmbH',
              name: 'Alpha GmbH',
              contactType: 'organisation',
              updated_at: '2026-01-01T00:00:00Z',
            },
          ],
          page: 0,
          size: 1,
          totalItems: 1,
          totalPages: 1,
        });
      }
      if (url.includes('/documents/types')) return json([]);
      if (url.includes('/documents/categories')) return json({ UNSORTED: 2 });
      if (url.includes('/documents/beleg-counts')) return json(counts);
      if (url.endsWith('/documents/batch') && method === 'POST') {
        const request = JSON.parse(body ?? '{}') as { ids: readonly string[]; action: string };
        if (request.action !== 'ACCEPT') {
          return json({ updated: request.ids.length, skipped: [] });
        }
        return json({
          updated: request.ids.filter((id) => id !== 'd2').length,
          skipped: [{ id: 'd2', reason: 'Ein Beleg kann nicht von EXPORTIERT nach OFFEN wechseln.' }],
        });
      }
      if (url.includes('/documents')) {
        return json({ content: documents, number: 0, size: 20, totalElements: 2, totalPages: 1 });
      }
      return json({});
    }),
  );
}

describe('Posteingang queue', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/documents');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('turns a selection into the office actions and reports what the rules refused', async () => {
    serve();
    renderWithProviders(<DocumentsPage />);

    expect(await screen.findByRole('link', { name: 'Rechnung Januar' })).toBeInTheDocument();

    // Nothing selected: no action panel. (The single-row "Annehmen" button of a fresh voucher is a different
    // control and stays where it is.)
    expect(screen.queryByRole('toolbar')).toBeNull();

    await userEvent.click(screen.getByRole('checkbox', { name: 'Dokument auswählen: Rechnung Januar' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Dokument auswählen: Rechnung Februar' }));

    expect(await screen.findByText('2 ausgewählt')).toBeInTheDocument();
    const panel = screen.getByRole('toolbar', { name: 'Aktionen für die Auswahl' });
    expect(within(panel).getByRole('button', { name: 'Zur Prüfung' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Mandant zuordnen' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Als Fehler markieren' })).toBeInTheDocument();

    await userEvent.click(within(panel).getByRole('button', { name: 'Annehmen' }));

    await waitFor(() => {
      const request = calls.find((call) => call.url.includes('/documents/batch'));
      expect(request?.method).toBe('POST');
      expect(JSON.parse(request?.body ?? '{}')).toEqual({
        ids: ['d1', 'd2'],
        action: 'ACCEPT',
      });
    });

    // The toast states the two halves: what moved, and the reason the other one did not.
    expect(await screen.findByText('1 von 2 Belegen geändert')).toBeInTheDocument();
    expect(
      screen.getByText('Ein Beleg kann nicht von EXPORTIERT nach OFFEN wechseln.'),
    ).toBeInTheDocument();

    // §5.1: the counters above the list are read again after a bulk action.
    await waitFor(() =>
      expect(calls.filter((call) => call.url.includes('/documents/beleg-counts')).length).toBeGreaterThan(1),
    );
  });

  it('sends the voucher status and client filters to the server and keeps them in the address', async () => {
    serve();
    renderWithProviders(<DocumentsPage />);
    await screen.findByRole('link', { name: 'Rechnung Januar' });

    await userEvent.selectOptions(screen.getByLabelText('Beleg-Status'), 'NEU');

    await waitFor(() => {
      const filtered = calls.filter(
        (call) => call.method === 'GET' && call.url.includes('/documents?') && call.url.includes('belegStatus=NEU'),
      );
      expect(filtered.length).toBeGreaterThan(0);
    });
    expect(window.location.search).toContain('belegStatus=NEU');

    // The client list arrives with the screen; the select offers it as soon as it is there.
    await screen.findByRole('option', { name: 'Alpha GmbH' });
    await userEvent.selectOptions(screen.getByLabelText('Mandant'), 'm-1');

    await waitFor(() => {
      expect(window.location.search).toContain('mandantId=m-1');
      const filtered = calls.filter(
        (call) => call.method === 'GET' && call.url.includes('/documents?') && call.url.includes('mandantId=m-1'),
      );
      expect(filtered.length).toBeGreaterThan(0);
    });

    // "Ohne Mandant" is an offer of the same select, not a missing value.
    expect(within(screen.getByLabelText('Mandant')).getByRole('option', { name: 'Ohne Mandant' })).toBeInTheDocument();
  });

  it('assigns a client through a searchable dialog instead of a dropdown', async () => {
    serve();
    renderWithProviders(<DocumentsPage />);
    await screen.findByRole('link', { name: 'Rechnung Januar' });

    await userEvent.click(screen.getByRole('checkbox', { name: 'Dokument auswählen: Rechnung Januar' }));
    const panel = await screen.findByRole('toolbar', { name: 'Aktionen für die Auswahl' });
    await userEvent.click(within(panel).getByRole('button', { name: 'Mandant zuordnen' }));

    const dialog = await screen.findByRole('dialog', { name: 'Mandant zuordnen' });
    await userEvent.type(within(dialog).getByLabelText('Mandant suchen'), 'Alp');
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Alpha GmbH' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Zuordnen' }));

    await waitFor(() => {
      const request = calls.find(
        (call) => call.method === 'POST' && call.url.includes('/documents/batch'),
      );
      expect(JSON.parse(request?.body ?? '{}')).toEqual({
        ids: ['d1'],
        action: 'ASSIGN_MANDANT',
        mandantId: 'm-1',
      });
    });
    expect(await screen.findByText('1 Beleg geändert')).toBeInTheDocument();
  });

  it('selects the whole page with one checkbox', async () => {
    serve();
    const { container } = renderWithProviders(<DocumentsPage />);
    await screen.findByRole('link', { name: 'Rechnung Januar' });

    await userEvent.click(screen.getByRole('checkbox', { name: 'Alle Dokumente dieser Seite auswählen' }));

    expect(await screen.findByText('2 ausgewählt')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Dokument auswählen: Rechnung Februar' })).toBeChecked();
    await expectNoA11yViolations(container);
  });
});
