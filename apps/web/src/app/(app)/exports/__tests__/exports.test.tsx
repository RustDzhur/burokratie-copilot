import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  expectNoA11yViolations,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from '@/test/render';
import ExportsPage from '../page';

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

const skippedResult = {
  id: 'batch-1',
  belegCount: 1,
  totalAmount: 119,
  filename: 'DATEV-Export_2026-01-01_bis_2026-01-31_ab12cd34.csv',
  skipped: [
    { documentId: 'doc-without-mandant', reason: 'Ohne Mandant' },
    { documentId: 'doc-without-mapping', reason: 'Kein Konto-Mapping' },
  ],
};

/** A backend that answers the two reads and the export the way the contract does. */
function serve(options: { readonly history?: readonly unknown[] } = {}): void {
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
              id: 'contact-1',
              display_name: 'Mandant A GmbH',
              kind: 'ORGANISATION',
              email: null,
              phone: null,
              city: null,
              updated_at: '2026-01-01T00:00:00Z',
            },
          ],
          page: 0,
          size: 1,
          totalItems: 1,
          totalPages: 1,
        });
      }
      if (url.endsWith('/exports/datev') && method === 'POST') {
        return json(skippedResult, 201);
      }
      if (url.endsWith('/exports/datev') && method === 'GET') {
        return json(options.history ?? []);
      }
      return json({});
    }),
  );
}

describe('DATEV exports page', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('renders the form and keeps the provisional-format warning visible', async () => {
    serve();
    const { container } = renderWithProviders(<ExportsPage />);

    expect(
      screen.getByText('Vorläufiges Format – nicht mit der Kanzlei abgestimmt'),
    ).toBeInTheDocument();
    expect(screen.getByText(/Anhang 1 des Lastenhefts/)).toBeInTheDocument();

    expect(screen.getByLabelText('Zeitraum von')).toBeInTheDocument();
    expect(screen.getByLabelText('Zeitraum bis')).toBeInTheDocument();
    expect(screen.getByLabelText('Mandant (optional)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'DATEV-Export erstellen' })).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText('Noch keine DATEV-Pakete.')).toBeInTheDocument());
    await expectNoA11yViolations(container);
  });

  it('shows the exported count and every skipped voucher with its reason', async () => {
    serve();
    renderWithProviders(<ExportsPage />);

    await screen.findByText('Noch keine DATEV-Pakete.');
    await userEvent.click(screen.getByRole('button', { name: 'DATEV-Export erstellen' }));

    expect(await screen.findByText('Im Paket')).toBeInTheDocument();
    expect(screen.getByText(/119,00/)).toBeInTheDocument();
    expect(screen.getByText('2 Belege übersprungen')).toBeInTheDocument();
    expect(screen.getByText('Ohne Mandant')).toBeInTheDocument();
    expect(screen.getByText('Kein Konto-Mapping')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CSV herunterladen' })).toBeInTheDocument();

    // The permanent warning does not disappear once a package exists.
    expect(
      screen.getByText('Vorläufiges Format – nicht mit der Kanzlei abgestimmt'),
    ).toBeInTheDocument();

    const post = calls.find((call) => call.method === 'POST');
    expect(post?.url).toBe('/api/v1/exports/datev');
    expect(post?.body).toContain('"periodFrom"');
    expect(post?.body).toContain('"periodTo"');
    expect(post?.body).toContain('"mandantId":null');
  });

  it('lists older packages and offers the file only when one was produced', async () => {
    serve({
      history: [
        {
          id: 'batch-old',
          createdAt: '2026-02-01T09:15:00Z',
          createdBy: 'user-1',
          createdByName: 'Nina Test',
          periodFrom: '2026-01-01',
          periodTo: '2026-01-31',
          mandantId: null,
          mandantName: null,
          belegCount: 2,
          totalAmount: 238,
          status: 'CREATED',
          filename: 'DATEV-Export_2026-01-01_bis_2026-01-31_ffffffff.csv',
          skippedCount: 1,
        },
        {
          id: 'batch-failed',
          createdAt: '2026-02-02T09:15:00Z',
          createdBy: 'user-1',
          createdByName: 'Nina Test',
          periodFrom: '2026-02-01',
          periodTo: '2026-02-28',
          mandantId: null,
          mandantName: null,
          belegCount: 0,
          totalAmount: 0,
          status: 'FAILED',
          filename: 'DATEV-Export_2026-02-01_bis_2026-02-28_eeeeeeee.csv',
          skippedCount: 0,
        },
      ],
    });
    renderWithProviders(<ExportsPage />);

    expect(await screen.findByText(/238,00/)).toBeInTheDocument();
    expect(screen.getAllByText('Alle Mandanten').length).toBeGreaterThan(0);
    expect(screen.getByText('Fehlgeschlagen')).toBeInTheDocument();
    // Exactly one download action for the two rows: the failed attempt has no file.
    expect(screen.getAllByRole('button', { name: 'Herunterladen' })).toHaveLength(1);
  });
});
