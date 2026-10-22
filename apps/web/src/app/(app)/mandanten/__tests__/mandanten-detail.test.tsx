import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, within } from '@/test/render';
import { formatDate } from '@/i18n/format';
import type { MandantDetail, MandantRequest } from '@/lib/api/types';
import MandantPage from '../[id]/page';

/**
 * One client's card (Kanzlei edition, §5.5).
 *
 * <p>The card has to answer "what is here" and "what is next": the newest vouchers, the deadlines that are
 * still ahead, and the one button that takes the person to the filtered voucher list. The numbers are the
 * server's; the test only checks that they arrive and that nothing is silently dropped.</p>
 */

vi.mock('next/navigation', () => ({
  usePathname: () => '/mandanten/m-1',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'm-1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const detail: MandantDetail = {
  id: 'm-1',
  displayName: 'Kartenhaus GmbH',
  contactType: 'organisation',
  email: 'kartenhaus@example.de',
  city: 'Hamburg',
  documentsTotal: 14,
  offen: 5,
  zuPruefen: 2,
  exportiert: 6,
  fehler: 1,
  deadlinesOpen: 1,
  lastActivityAt: '2026-02-02',
  health: 'RISK',
  lastDocuments: [
    {
      id: 'doc-1',
      filename: 'rechnung-2026-02.pdf',
      belegStatus: 'ZU_PRUEFEN',
      date: '2026-02-02',
    },
    {
      id: 'doc-2',
      filename: 'rechnung-2026-01.pdf',
      belegStatus: 'EXPORTIERT',
      date: '2026-02-01',
    },
  ],
  openDeadlines: [
    {
      id: 'deadline-1',
      deadlineType: 'DOCUMENT_DATE',
      dueDate: '2026-11-05',
      requiresVerification: false,
      documentId: 'doc-1',
    },
    {
      id: 'deadline-2',
      deadlineType: 'ASSUMED_LEGAL',
      dueDate: '2026-11-20',
      requiresVerification: true,
      documentId: null,
    },
  ],
};

function serve(requests: readonly MandantRequest[] = []): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/auth/csrf')) {
        return new Response(JSON.stringify({ token: 'test' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url.includes('/mandant-requests')) {
        return new Response(JSON.stringify(requests), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(JSON.stringify(detail), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
}

const request: MandantRequest = {
  id: 'request-1',
  mandantId: 'm-1',
  mandantName: 'Kartenhaus GmbH',
  mandantEmail: 'kartenhaus@example.de',
  documentId: 'doc-1',
  documentFilename: 'rechnung-2026-02.pdf',
  subject: 'Anfrage: fehlende Belege – Kartenhaus GmbH',
  body: 'Sehr geehrte Damen und Herren,',
  status: 'SENT',
  createdAt: '2026-02-04T09:00:00Z',
  sentAt: '2026-02-04T09:01:00Z',
  lastReminderAt: null,
  reminderCount: 0,
  answeredAt: null,
  closedAt: null,
  note: null,
  createdBy: 'u-1',
};

describe('Mandant card', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows the state, the counters, the newest vouchers and the open deadlines', async () => {
    serve();
    const { container } = renderWithProviders(<MandantPage />);

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Kartenhaus GmbH' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Risiko')).toBeInTheDocument();
    expect(screen.getByText('Etwas ist liegen geblieben.')).toBeInTheDocument();

    // The counters of the server, not a recount in the browser.
    expect(screen.getByText('Belege insgesamt')).toBeInTheDocument();
    expect(screen.getByText('14')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 2, name: 'Offene Fristen' }),
    ).toBeInTheDocument();

    const documents = await screen.findAllByRole('row', { name: /rechnung-2026/ });
    expect(documents).toHaveLength(2);
    expect(within(documents[0]!).getByRole('link', { name: 'rechnung-2026-02.pdf' })).toHaveAttribute(
      'href',
      '/documents/doc-1',
    );
    expect(within(documents[0]!).getByText('Zu prüfen')).toBeInTheDocument();
    expect(within(documents[1]!).getByText('Exportiert')).toBeInTheDocument();
    expect(within(documents[0]!).getByText(formatDate('2026-02-02', 'de'))).toBeInTheDocument();

    // A deadline from the document and one that still needs a person to confirm it.
    expect(screen.getByText('Frist laut Dokument')).toBeInTheDocument();
    expect(screen.getByText('Angenommene Frist')).toBeInTheDocument();
    expect(screen.getByText('ungeprüft')).toBeInTheDocument();

    await expectNoA11yViolations(container);
  });

  it('sends the client along to the voucher list instead of making the person pick it again', async () => {
    serve();
    renderWithProviders(<MandantPage />);

    expect(await screen.findByRole('link', { name: 'Belege anzeigen' })).toHaveAttribute(
      'href',
      '/documents?party=m-1',
    );
    expect(screen.getByRole('link', { name: 'Zurück zur Liste' })).toHaveAttribute(
      'href',
      '/mandanten',
    );
  });

  it('lists the requests to the client with their state and offers to ask again', async () => {
    serve([request]);
    renderWithProviders(<MandantPage />);

    expect(
      await screen.findByRole('heading', { level: 2, name: 'Anfragen' }),
    ).toBeInTheDocument();
    expect(await screen.findByText('Anfrage: fehlende Belege – Kartenhaus GmbH')).toBeInTheDocument();
    expect(screen.getByText('Gesendet')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erneut anfordern' })).toBeInTheDocument();
  });
});
