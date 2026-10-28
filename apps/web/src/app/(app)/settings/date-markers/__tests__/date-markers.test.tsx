import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement, ReactNode } from 'react';
import {
  expectNoA11yViolations,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  within,
} from '@/test/render';
import { SessionProvider } from '@/lib/auth/session-context';
import type { DateMarker, SessionUser } from '@/lib/api/types';
import DateMarkersPage from '../page';

const items: readonly DateMarker[] = [
  {
    id: 'sys-zahlungsziel',
    kind: 'PAYMENT_DEADLINE',
    phrase: 'zahlungsziel',
    label: null,
    enabled: true,
    system: true,
    tenantOverride: false,
  },
  {
    id: 'sys-spaetestens',
    kind: 'DEADLINE',
    phrase: 'spätestens',
    label: null,
    enabled: true,
    system: true,
    tenantOverride: false,
  },
  {
    id: 'own-hausbesuch',
    kind: 'APPOINTMENT',
    phrase: 'hausbesuch',
    label: 'Hausbesuch der Behörde',
    enabled: true,
    system: false,
    tenantOverride: false,
  },
  {
    id: 'own-vom',
    kind: 'DOCUMENT_DATE',
    phrase: 'vom',
    label: null,
    enabled: false,
    system: false,
    tenantOverride: true,
  },
];

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

/** A backend that serves the vocabulary and answers every mutation the way the contract does. */
function serve(options: { readonly deleted?: number } = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      const body = typeof init?.body === 'string' ? init.body : null;
      calls.push({ url, method, body });

      if (url.includes('/settings/date-markers:reset'))
        return json({ deleted: options.deleted ?? 2 });
      if (method === 'GET' && url.includes('/settings/date-markers')) {
        return json({ items, count: items.length });
      }
      if (method === 'POST' && url.includes('/settings/date-markers')) {
        return json({ ...items[2], id: 'created', phrase: 'neu' }, 201);
      }
      if (method === 'PATCH') return json({ ...items[2], enabled: false });
      if (method === 'DELETE') return new Response(null, { status: 204 });
      return json({});
    }),
  );
}

const user = (permissions: readonly string[]): SessionUser => ({
  id: 'u1',
  name: 'Nina Test',
  email: 'nina@example.de',
  roles: [],
  workspaceId: 'w1',
  workspaceName: 'Testkabinett',
  workspaceType: 'private',
  permissions,
});

const EDITOR = user(['settings.view', 'settings.manage_rules']);
const VIEWER = user(['settings.view']);

function withSession(userValue: SessionUser, ui: ReactNode): ReactElement {
  return <SessionProvider user={userValue}>{ui}</SessionProvider>;
}

function rowFor(phrase: string): HTMLElement {
  const row = screen.getAllByTestId('date-marker').find((item) => within(item).queryByText(phrase));
  if (!row) throw new Error(`no row for ${phrase}`);
  return row;
}

describe('DateMarkersPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows the vocabulary grouped by kind, with standard words, own words and a switched-off default marked', async () => {
    serve();
    const { container } = renderWithProviders(<DateMarkersPage />);

    expect(await screen.findByText('zahlungsziel')).toBeInTheDocument();
    expect(
      calls.some((call) => call.method === 'GET' && call.url === '/api/v1/settings/date-markers'),
    ).toBe(true);
    expect(screen.getAllByTestId('date-marker')).toHaveLength(4);
    expect(screen.getByText('3 Standardwörter · 1 eigenes Wort')).toBeInTheDocument();

    expect(screen.getByRole('heading', { level: 2, name: /Zahlungsziel/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /Termin/ })).toBeInTheDocument();
    expect(screen.getAllByText('Standard')).toHaveLength(2);
    expect(screen.getAllByText('Eigenes')).toHaveLength(2);
    expect(screen.getByText('Ersetzt Standard')).toBeInTheDocument();
    expect(screen.getByText('Nicht aktiv')).toBeInTheDocument();
    expect(screen.getByText('Hausbesuch der Behörde')).toBeInTheDocument();

    await expectNoA11yViolations(container);
  });

  it('filters by word and by kind, and says how many of the words are shown', async () => {
    serve();
    renderWithProviders(<DateMarkersPage />);
    await screen.findByText('zahlungsziel');
    expect(screen.getByText('4 von 4 Wörtern')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Nach Wort oder Bezeichnung suchen'), 'haus');
    expect(screen.getByText('1 von 4 Wörtern')).toBeInTheDocument();
    expect(screen.getByText('hausbesuch')).toBeInTheDocument();
    expect(screen.queryByText('zahlungsziel')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Leeren' }));
    await userEvent.selectOptions(
      screen.getByLabelText('Datumsart', { selector: '#date-marker-filter-kind' }),
      'DEADLINE',
    );
    expect(screen.getByText('spätestens')).toBeInTheDocument();
    expect(screen.queryByText('hausbesuch')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Filter zurücksetzen' }));
    expect(screen.getByText('4 von 4 Wörtern')).toBeInTheDocument();
  });

  it('adds an own word with kind, phrase and optional label', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <DateMarkersPage />));
    await screen.findByText('zahlungsziel');

    const phrase = screen.getByLabelText('Wort', { selector: '#date-marker-add-phrase' });
    await userEvent.type(phrase, 'a');
    await userEvent.click(screen.getByRole('button', { name: 'Hinzufügen' }));
    expect(
      screen.getByText('Bitte ein Wort mit mindestens 2 Zeichen angeben.'),
    ).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST')).toBe(false);

    await userEvent.clear(phrase);
    await userEvent.type(phrase, '  Hausbesuch  ');
    await userEvent.selectOptions(
      screen.getByLabelText('Datumsart', { selector: '#date-marker-add-kind' }),
      'APPOINTMENT',
    );
    await userEvent.type(
      screen.getByLabelText('Bezeichnung (optional)', { selector: '#date-marker-add-label' }),
      'Hausbesuch der Behörde',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Hinzufügen' }));

    await waitFor(() =>
      expect(
        calls.some(
          (call) => call.method === 'POST' && call.url.endsWith('/api/v1/settings/date-markers'),
        ),
      ).toBe(true),
    );
    const post = calls.find(
      (call) => call.method === 'POST' && call.url.endsWith('/api/v1/settings/date-markers'),
    );
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      kind: 'APPOINTMENT',
      phrase: 'Hausbesuch',
      label: 'Hausbesuch der Behörde',
    });
    expect(await screen.findByText('Wort hinzugefügt')).toBeInTheDocument();
  });

  it('switches a standard word off by adding the same word as an own, inactive one', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <DateMarkersPage />));
    await screen.findByText('zahlungsziel');

    const row = rowFor('zahlungsziel');
    expect(within(row).queryByRole('button', { name: 'Löschen' })).toBeNull();
    await userEvent.click(within(row).getByRole('switch', { name: 'Aktiv' }));

    await waitFor(() =>
      expect(
        calls.some(
          (call) => call.method === 'POST' && call.url.endsWith('/api/v1/settings/date-markers'),
        ),
      ).toBe(true),
    );
    const post = calls.find(
      (call) => call.method === 'POST' && call.url.endsWith('/api/v1/settings/date-markers'),
    );
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      kind: 'PAYMENT_DEADLINE',
      phrase: 'zahlungsziel',
      enabled: false,
    });
    // The default's own id must not be patched: the server answers 409 for that.
    expect(calls.some((call) => call.method === 'PATCH')).toBe(false);
    expect(await screen.findByText('Standardwort ausgeschaltet')).toBeInTheDocument();
  });

  it('switches an own word off with PATCH and deletes it with DELETE', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <DateMarkersPage />));
    await screen.findByText('hausbesuch');

    await userEvent.click(within(rowFor('hausbesuch')).getByRole('switch', { name: 'Aktiv' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'PATCH')).toBe(true));
    const patch = calls.find((call) => call.method === 'PATCH');
    expect(patch?.url).toContain('/api/v1/settings/date-markers/own-hausbesuch');
    expect(JSON.parse(patch?.body ?? '{}')).toEqual({ enabled: false });

    await userEvent.click(within(rowFor('hausbesuch')).getByRole('button', { name: 'Löschen' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'DELETE')).toBe(true));
    expect(calls.find((call) => call.method === 'DELETE')?.url).toContain(
      '/api/v1/settings/date-markers/own-hausbesuch',
    );
    expect(await screen.findByText('Wort gelöscht')).toBeInTheDocument();
  });

  it('resets to the defaults only after the confirmation, and says how many words were removed', async () => {
    serve({ deleted: 2 });
    renderWithProviders(withSession(EDITOR, <DateMarkersPage />));
    await screen.findByText('zahlungsziel');

    await userEvent.click(screen.getByRole('button', { name: 'Auf Standard zurücksetzen' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Auf den Standard zurücksetzen?')).toBeInTheDocument();
    expect(calls.some((call) => call.url.includes(':reset'))).toBe(false);

    await userEvent.click(within(dialog).getByRole('button', { name: 'Zurücksetzen' }));
    await waitFor(() => expect(calls.some((call) => call.url.includes(':reset'))).toBe(true));
    expect(calls.find((call) => call.url.includes(':reset'))?.url).toContain(
      '/api/v1/settings/date-markers:reset',
    );
    expect(
      await screen.findByText('2 eigene Wörter gelöscht – es gelten wieder die Standardwörter.'),
    ).toBeInTheDocument();
  });

  it('shows the list read-only to a person who may see settings but not change them', async () => {
    serve();
    renderWithProviders(withSession(VIEWER, <DateMarkersPage />));
    await screen.findByText('zahlungsziel');

    expect(
      screen.getByText(/Sie dürfen den Wortschatz ansehen, aber nicht ändern/),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Eigenes Wort hinzufügen' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Auf Standard zurücksetzen' })).toBeNull();
    for (const control of screen.getAllByRole('switch')) {
      expect(control).toBeDisabled();
    }
  });

  it('shows no vocabulary at all to a person without settings.view, and does not call the API', async () => {
    serve();
    renderWithProviders(withSession(user([]), <DateMarkersPage />));

    expect(screen.getByText('Kein Zugriff')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Zurück zu den Einstellungen' })).toHaveAttribute(
      'href',
      '/settings',
    );
    expect(calls.filter((call) => call.url.includes('date-markers'))).toHaveLength(0);
  });

  it('shows the error state with a retry when the vocabulary cannot be loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json({ code: 'INTERNAL_ERROR', message: 'boom' }, 500)),
    );
    renderWithProviders(<DateMarkersPage />);

    expect(await screen.findByText('Im Backend ist ein Fehler aufgetreten.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
  });
});
