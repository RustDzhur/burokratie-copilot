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
import type { MandantRule, SessionUser } from '@/lib/api/types';
import MandantRulesPage from '../page';

const rules: readonly MandantRule[] = [
  {
    id: 'rule-1',
    signalTyp: 'ABSENDER_EMAIL',
    muster: 'rechnung@acme.example',
    mandantId: 'contact-1',
    mandantName: 'ACME GmbH',
    quelle: 'MANUELL',
    treffer: 3,
    letzteNutzung: '2026-03-01T10:00:00Z',
    createdAt: '2026-02-01T10:00:00Z',
  },
  {
    id: 'rule-2',
    signalTyp: 'IBAN',
    muster: 'DE89370400440532013000',
    mandantId: 'contact-2',
    mandantName: 'Beta Handel GmbH',
    quelle: 'AUTO',
    treffer: 0,
    letzteNutzung: null,
    createdAt: '2026-02-02T10:00:00Z',
  },
];

interface Call {
  readonly url: string;
  readonly method: string;
}

const calls: Call[] = [];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A backend that serves the rules and removes exactly the one the screen asked to remove. */
function serve(items: readonly MandantRule[] = rules): void {
  let current = [...items];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      calls.push({ url, method });
      if (url.includes('/auth/csrf')) return json({ token: 'test' });
      if (method === 'DELETE') {
        const id = url.split('/').pop();
        current = current.filter((rule) => rule.id !== id);
        return new Response(null, { status: 204 });
      }
      return json({ items: current, count: current.length });
    }),
  );
}

const user = (permissions: readonly string[]): SessionUser => ({
  id: 'u1',
  name: 'Nina Test',
  email: 'nina@example.de',
  roles: [],
  workspaceId: 'w1',
  workspaceName: 'Testkanzlei',
  permissions,
});

const EDITOR = user(['settings.view', 'settings.manage_rules']);
const VIEWER = user(['settings.view']);

function withSession(userValue: SessionUser, ui: ReactNode): ReactElement {
  return <SessionProvider user={userValue}>{ui}</SessionProvider>;
}

function rowFor(text: string): HTMLElement {
  const row = screen.getAllByRole('row').find((item) => within(item).queryByText(text));
  if (!row) throw new Error(`no row for ${text}`);
  return row;
}

describe('MandantRulesPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('lists signal, pattern, client, hits, date and origin, and explains where rules come from', async () => {
    serve();
    const { container } = renderWithProviders(<MandantRulesPage />);

    expect(await screen.findByText('rechnung@acme.example')).toBeInTheDocument();
    const learned = rowFor('rechnung@acme.example');
    expect(within(learned).getByText('E-Mail des Absenders')).toBeInTheDocument();
    expect(within(learned).getByText('ACME GmbH')).toBeInTheDocument();
    expect(within(learned).getByText('3')).toBeInTheDocument();
    expect(within(learned).getByText('Gelernt')).toBeInTheDocument();
    expect(within(learned).getByText(/2026/)).toBeInTheDocument();

    const automatic = rowFor('DE89370400440532013000');
    expect(within(automatic).getByText('IBAN')).toBeInTheDocument();
    expect(within(automatic).getByText('Beta Handel GmbH')).toBeInTheDocument();
    expect(within(automatic).getByText('Nie')).toBeInTheDocument();
    expect(within(automatic).getByText('Automatisch')).toBeInTheDocument();
    expect(screen.getByText('2 von 2 Regeln')).toBeInTheDocument();

    // The two consequences a person must know before touching a rule are on the screen, not in a manual.
    expect(screen.getByText(/Jede Zuordnung von Hand wird gelernt/)).toBeInTheDocument();
    expect(screen.getByText(/Löschen Sie eine Regel, ordnet der Copilot keinen weiteren Beleg/)).toBeInTheDocument();

    await expectNoA11yViolations(container);
  });

  it('deletes one rule after confirmation and stops showing it', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <MandantRulesPage />));
    await screen.findByText('rechnung@acme.example');

    await userEvent.click(
      within(rowFor('rechnung@acme.example')).getByRole('button', { name: 'Löschen' }),
    );
    const confirm = await screen.findByRole('dialog');
    expect(within(confirm).getByText('Regel löschen?')).toBeInTheDocument();
    expect(within(confirm).getByText(/E-Mail des Absenders/)).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);

    await userEvent.click(within(confirm).getByRole('button', { name: 'Löschen' }));

    await waitFor(() =>
      expect(calls.some((call) => call.method === 'DELETE' && call.url.endsWith('/rule-1'))).toBe(
        true,
      ),
    );
    expect(await screen.findByText('Regel gelöscht')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('rechnung@acme.example')).toBeNull());
    expect(screen.getByText('1 von 1 Regel')).toBeInTheDocument();
  });

  it('filters by pattern, client or signal name', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <MandantRulesPage />));
    await screen.findByText('rechnung@acme.example');

    await userEvent.type(
      screen.getByLabelText('Nach Signal, Muster oder Mandant suchen'),
      'beta',
    );
    expect(screen.getByText('1 von 2 Regeln')).toBeInTheDocument();
    expect(screen.getByText('DE89370400440532013000')).toBeInTheDocument();
    expect(screen.queryByText('rechnung@acme.example')).toBeNull();
  });

  it('shows the rules read-only to a person who may see settings but not change them', async () => {
    serve();
    renderWithProviders(withSession(VIEWER, <MandantRulesPage />));
    await screen.findByText('rechnung@acme.example');

    expect(screen.getByText(/Sie dürfen die Regeln ansehen, aber nicht löschen/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Löschen' })).toBeNull();
  });

  it('shows nothing to a person without settings.view and does not call the API', () => {
    serve();
    renderWithProviders(withSession(user(['tasks.view']), <MandantRulesPage />));

    expect(screen.getByText('Kein Zugriff')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Zurück zu den Einstellungen' })).toHaveAttribute(
      'href',
      '/settings',
    );
    expect(calls.filter((call) => call.url.includes('mandant-rules'))).toHaveLength(0);
  });

  it('explains the empty state and what will fill it', async () => {
    serve([]);
    renderWithProviders(withSession(EDITOR, <MandantRulesPage />));

    expect(await screen.findByText('Noch keine Regel')).toBeInTheDocument();
    expect(screen.getByText(/Sobald Sie einen Beleg von Hand einem Mandanten zuordnen/)).toBeInTheDocument();
  });

  it('shows the error state with a retry when the rules cannot be loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json({ code: 'INTERNAL_ERROR', message: 'boom' }, 500)),
    );
    renderWithProviders(<MandantRulesPage />);

    expect(await screen.findByText('Im Backend ist ein Fehler aufgetreten.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
  });
});
