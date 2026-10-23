import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { catalogues } from '@/i18n/messages';
import type { SetupState } from '@/lib/api/types';
import SetupPage from '../page';

/**
 * The first-run wizard of §6.1: three steps, and the person leaves them in the Posteingang.
 *
 * <p>Checked are the promises the step makes: step 1 asks for the name of the office and for nothing else,
 * step 2 creates clients by hand or from a CSV file and reports what it skipped, and step 3 ends in
 * {@code /documents} - the Posteingang of the Kanzlei menu.</p>
 */

const push = vi.fn();
const replace = vi.fn();
// One stable router object: the page's loading effect depends on it, and a new object per render would make
// the effect run again on every keystroke (which is not what Next does and would hide the real behaviour).
const router = { push, replace, prefetch: vi.fn(), back: vi.fn() };
vi.mock('next/navigation', () => ({
  usePathname: () => '/setup',
  useRouter: () => router,
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

interface Call {
  readonly url: string;
  readonly method: string;
  readonly body: string | null;
}

const calls: Call[] = [];

let state: SetupState;
let clients: { id: string; displayName: string; contactType: string; email: string | null; city: string | null }[];
let importReport: unknown;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function serve(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      const body = typeof init?.body === 'string' ? init.body : null;
      calls.push({ url, method, body });

      if (url.includes('/auth/csrf')) return new Response(null, { status: 204 });
      if (url.includes('/setup/cabinet')) {
        state = { ...state, cabinetName: (JSON.parse(body ?? '{}') as { name?: string }).name ?? '' };
        return json(state);
      }
      if (url.includes('/setup/complete')) {
        state = { ...state, completed: true };
        return new Response(null, { status: 204 });
      }
      if (url.endsWith('/setup')) return json(state);
      if (url.includes('/contacts/import')) return json(importReport);
      if (url.includes('/contacts') && method === 'POST') {
        const payload = JSON.parse(body ?? '{}') as {
          name: string;
          email?: string;
          city?: string;
        };
        return json(
          {
            id: 'c-new',
            display_name: payload.name,
            kind: 'ORGANISATION',
            email: payload.email ?? null,
            city: payload.city ?? null,
            updated_at: '2026-10-23T08:00:00Z',
          },
          201,
        );
      }
      if (url.includes('/mandanten')) return json(clients);
      return json({});
    }),
  );
}

async function toStep(user: ReturnType<typeof userEvent.setup>, step: 2 | 3): Promise<void> {
  await screen.findByRole('heading', { name: 'Wer sind Sie?' });
  await user.click(screen.getByRole('button', { name: 'Weiter' }));
  await screen.findByRole('heading', { name: 'Ihre Mandanten' });
  if (step === 3) {
    await user.click(screen.getByRole('button', { name: 'Weiter' }));
    await screen.findByRole('heading', { name: 'Erste Dokumente' });
  }
}

beforeEach(() => {
  state = {
    cabinetName: 'Muster Kanzlei',
    mandantCount: 0,
    completed: false,
    canEditCabinet: true,
  };
  clients = [];
  importReport = {
    total: 3,
    created: 2,
    skipped: 1,
    lines: [
      { line: 1, status: 'CREATED', name: 'Müller Bau GmbH', reason: null },
      { line: 2, status: 'CREATED', name: 'Schmidt Physiotherapie', reason: null },
      { line: 3, status: 'SKIPPED', name: null, reason: 'Der Name fehlt' },
    ],
  };
});

afterEach(() => {
  vi.unstubAllGlobals();
  calls.length = 0;
  push.mockClear();
  replace.mockClear();
});

describe('first-run wizard', () => {
  it('starts at "Wer sind Sie?" with the name of the office as the one mandatory field', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<SetupPage />);

    expect(await screen.findByRole('heading', { name: 'Wer sind Sie?' })).toBeInTheDocument();
    const name = screen.getByLabelText('Name der Kanzlei') as HTMLInputElement;
    expect(name.value).toBe('Muster Kanzlei');
    expect(screen.getAllByText('Schritt 1 von 3').length).toBeGreaterThan(0);
    // Nothing else is asked: no sources, no notifications, no accessibility on this step.
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('switch')).toBeNull();

    await user.clear(name);
    await user.type(name, 'Kanzlei Weber');
    await user.click(screen.getByRole('button', { name: 'Weiter' }));

    await waitFor(() =>
      expect(calls.some((call) => call.url.endsWith('/setup/cabinet') && call.method === 'PUT')).toBe(
        true,
      ),
    );
    const saved = calls.find((call) => call.url.endsWith('/setup/cabinet'));
    expect(JSON.parse(saved?.body ?? '{}')).toEqual({ name: 'Kanzlei Weber' });
    expect(await screen.findByRole('heading', { name: 'Ihre Mandanten' })).toBeInTheDocument();
    expect(screen.getAllByText('Schritt 2 von 3').length).toBeGreaterThan(0);
  });

  it('creates clients by hand and continues to the documents step', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<SetupPage />);
    await toStep(user, 2);

    await user.type(screen.getByLabelText('Name des Mandanten'), 'Alpha GmbH');
    await user.type(screen.getByLabelText('E-Mail-Adresse (optional)'), 'alpha@example.de');
    await user.type(screen.getByLabelText('Ort (optional)'), 'Berlin');
    await user.click(screen.getByRole('button', { name: 'Mandant anlegen' }));

    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/contacts') && call.method === 'POST')).toBe(
        true,
      ),
    );
    const created = calls.find((call) => call.url.includes('/contacts') && call.method === 'POST');
    expect(JSON.parse(created?.body ?? '{}')).toEqual({
      name: 'Alpha GmbH',
      kind: 'ORGANISATION',
      email: 'alpha@example.de',
      city: 'Berlin',
    });
    expect(await screen.findByText('Alpha GmbH')).toBeInTheDocument();

    // Three clients are the suggestion, not a condition: the wizard continues with one.
    await user.click(screen.getByRole('button', { name: 'Weiter' }));
    expect(await screen.findByRole('heading', { name: 'Erste Dokumente' })).toBeInTheDocument();
    expect(screen.getAllByText('Schritt 3 von 3').length).toBeGreaterThan(0);
  });

  it('imports clients from a CSV file and names every skipped line', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<SetupPage />);
    await toStep(user, 2);

    const file = new File(
      ['name;email;city\nMüller Bau GmbH;info@example.de;Berlin\n;leer@example.de;Köln'],
      'mandanten.csv',
      { type: 'text/csv' },
    );
    await user.upload(screen.getByLabelText('CSV-Datei wählen'), file);

    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/contacts/import'))).toBe(true),
    );
    const imported = calls.find((call) => call.url.includes('/contacts/import'));
    expect(JSON.parse(imported?.body ?? '{}').content).toContain('Müller Bau GmbH');

    expect((await screen.findAllByText('2 angelegt, 1 übersprungen.')).length).toBeGreaterThan(0);
    expect(screen.getByText('Zeile 3: Der Name fehlt')).toBeInTheDocument();
  });

  it('leaves step 3 in the Posteingang', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<SetupPage />);
    await toStep(user, 3);

    await user.click(screen.getByRole('button', { name: 'Zum Posteingang' }));

    await waitFor(() =>
      expect(calls.some((call) => call.url.endsWith('/setup/complete') && call.method === 'POST')).toBe(
        true,
      ),
    );
    await waitFor(() => expect(push).toHaveBeenCalledWith('/documents'));
  });

  it('sends a cabinet that is already set up straight to the Posteingang', async () => {
    state = { ...state, completed: true };
    serve();
    renderWithProviders(<SetupPage />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/documents'));
    expect(screen.queryByRole('heading', { name: 'Wer sind Sie?' })).toBeNull();
  });

  it('keeps the empty states of §6.2 in place', async () => {
    // The Posteingang says "Gut so" instead of showing an error, and a cabinet without clients says what to do.
    expect(catalogues.de.documents['empty.title']).toContain('Gut so');
    expect(catalogues.de.mandanten['empty.description']).toContain(
      'Legen Sie Ihren ersten Mandanten an',
    );
  });
});
