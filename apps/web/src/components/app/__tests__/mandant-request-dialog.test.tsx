import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import type { MandantRequest, MandantRequestPreview } from '@/lib/api/types';
import { MandantRequestDialog } from '../mandant-request-dialog';

/**
 * The letter dialog of the voucher card (Kanzlei edition, §5.7).
 *
 * <p>The two things the office depends on are checked here: the dialog opens with the German text the
 * server generated, and "Senden" really performs the two calls — store the draft, then hand it over. The
 * failure path is the third: when the deployment has no postausgang, the person is told in words and the
 * dialog does not pretend the letter left the house.</p>
 */

interface Call {
  readonly url: string;
  readonly method: string;
  readonly body: unknown;
}

const calls: Call[] = [];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const preview: MandantRequestPreview = {
  subject: 'Anfrage: fehlende Belege – Kartenhaus GmbH – Rechnung vom 03.02.2026',
  body: 'Sehr geehrte Damen und Herren,\n\nfür die Buchung der Rechnung vom 03.02.2026 fehlt uns: die vollständigen Angaben zum Beleg.\n\nVielen Dank und freundliche Grüße\nKanzlei Test',
  mandantName: 'Kartenhaus GmbH',
  mandantEmail: 'buchhaltung@kartenhaus.example',
};

const draft: MandantRequest = {
  id: 'req-1',
  mandantId: 'm-1',
  mandantName: 'Kartenhaus GmbH',
  mandantEmail: 'buchhaltung@kartenhaus.example',
  documentId: 'doc-1',
  documentFilename: 'rechnung.pdf',
  subject: preview.subject,
  body: preview.body,
  status: 'DRAFT',
  createdAt: '2026-02-04T09:00:00Z',
  sentAt: null,
  lastReminderAt: null,
  reminderCount: 0,
  answeredAt: null,
  closedAt: null,
  note: null,
  createdBy: 'u-1',
};

const sent: MandantRequest = {
  ...draft,
  status: 'SENT',
  sentAt: '2026-02-04T09:01:00Z',
};

function serve(options: { readonly sendStatus?: number } = {}): void {
  const sendStatus = options.sendStatus ?? 200;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : null;
      calls.push({ url, method, body });

      if (url.includes('/auth/csrf')) return json({ token: 'test' });
      if (url.includes('/mandant-requests/preview')) {
        const missing: string[] = Array.isArray(body?.missing) ? body.missing : [];
        return json({
          ...preview,
          body: missing.includes('AMOUNT')
            ? preview.body.replace('die vollständigen Angaben zum Beleg', 'der Rechnungsbetrag')
            : preview.body,
        });
      }
      if (url.includes('/mandant-requests/req-1/send')) {
        return sendStatus === 200
          ? json(sent)
          : json({ code: 'MAIL_NOT_CONFIGURED', message: 'E-Mail-Versand ist nicht eingerichtet' }, sendStatus);
      }
      if (url.endsWith('/mandant-requests') && method === 'POST') {
        return json({ ...draft, body: body?.body ?? draft.body, subject: body?.subject ?? draft.subject }, 201);
      }
      return json({});
    }),
  );
}

describe('MandantRequestDialog', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('opens with the generated German letter and the recipient', async () => {
    serve();
    renderWithProviders(
      <MandantRequestDialog open onClose={vi.fn()} mandantId="m-1" documentId="doc-1" />,
    );

    expect(await screen.findByDisplayValue(preview.subject)).toBeInTheDocument();
    expect(await screen.findByText('Kartenhaus GmbH · buchhaltung@kartenhaus.example')).toBeInTheDocument();
    await waitFor(() =>
      expect((screen.getByLabelText('Text') as HTMLTextAreaElement).value).toContain(
        'Sehr geehrte Damen und Herren',
      ),
    );
    expect(screen.getByRole('heading', { name: 'Belege anfordern' })).toBeInTheDocument();

    // The four things a bookkeeper may mark as missing.
    expect(screen.getByRole('checkbox', { name: 'Betrag' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Datum' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Belegnummer' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Anlage' })).toBeInTheDocument();
  });

  it('regenerates the text when a missing item is selected', async () => {
    serve();
    renderWithProviders(
      <MandantRequestDialog open onClose={vi.fn()} mandantId="m-1" documentId="doc-1" />,
    );

    await waitFor(() =>
      expect((screen.getByLabelText('Text') as HTMLTextAreaElement).value).toContain(
        'die vollständigen Angaben zum Beleg',
      ),
    );
    await userEvent.click(screen.getByRole('checkbox', { name: 'Betrag' }));

    await waitFor(() =>
      expect((screen.getByLabelText('Text') as HTMLTextAreaElement).value).toContain(
        'der Rechnungsbetrag',
      ),
    );
    expect(
      calls.some(
        (call) =>
          call.url.includes('/mandant-requests/preview') &&
          Array.isArray((call.body as { missing?: string[] }).missing) &&
          (call.body as { missing: string[] }).missing.includes('AMOUNT'),
      ),
    ).toBe(true);
  });

  it('stores the draft and then hands it over when Senden is pressed', async () => {
    serve();
    const onSent = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <MandantRequestDialog
        open
        onClose={onClose}
        mandantId="m-1"
        documentId="doc-1"
        onSent={onSent}
      />,
    );

    await screen.findByDisplayValue(preview.subject);
    await userEvent.click(screen.getByRole('button', { name: 'Senden' }));

    await waitFor(() => expect(onSent).toHaveBeenCalledWith(sent));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(calls.filter((call) => call.method === 'POST' && call.url.endsWith('/mandant-requests'))).toHaveLength(1);
    expect(
      calls.some((call) => call.method === 'POST' && call.url.endsWith('/mandant-requests/req-1/send')),
    ).toBe(true);
  });

  it('says in words that mail is not set up and does not claim the letter was sent', async () => {
    serve({ sendStatus: 409 });
    const onSent = vi.fn();
    renderWithProviders(
      <MandantRequestDialog open onClose={vi.fn()} mandantId="m-1" documentId="doc-1" onSent={onSent} />,
    );

    await screen.findByDisplayValue(preview.subject);
    await userEvent.click(screen.getByRole('button', { name: 'Senden' }));

    expect(await screen.findByText(/E-Mail-Versand ist noch nicht eingerichtet/)).toBeInTheDocument();
    expect(onSent).not.toHaveBeenCalled();
    // The draft was written before the handover was refused; the history therefore shows an Entwurf.
    expect(calls.filter((call) => call.method === 'POST' && call.url.endsWith('/mandant-requests'))).toHaveLength(1);
  });
});
