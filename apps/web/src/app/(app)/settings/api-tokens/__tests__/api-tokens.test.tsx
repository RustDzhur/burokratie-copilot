import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor, within } from '@/test/render';
import { SessionProvider } from '@/lib/auth/session-context';
import type { ApiToken, SessionUser } from '@/lib/api/types';
import ApiTokensPage from '../page';

const tokens: readonly ApiToken[] = [
  {
    id: 't1',
    name: 'Desktop-Bridge Empfang',
    prefix: 'bc_12345678_',
    scopes: ['documents:write'],
    createdAt: '2026-10-01T10:00:00Z',
    lastUsedAt: '2026-10-02T11:30:00Z',
    revokedAt: null,
    active: true,
  },
  {
    id: 't2',
    name: 'Alter Scanner',
    prefix: 'bc_abcdef12_',
    scopes: ['documents:read'],
    createdAt: '2026-09-01T10:00:00Z',
    lastUsedAt: null,
    revokedAt: '2026-09-15T09:00:00Z',
    active: false,
  },
];

const availableScopes = ['documents:write', 'documents:read', 'agents.view', 'agents.run'];

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

/** A backend that serves the token list and answers create and revoke the way the contract does. */
function serve(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      const body = typeof init?.body === 'string' ? init.body : null;
      calls.push({ url, method, body });

      if (method === 'POST' && url.endsWith('/revoke')) return new Response(null, { status: 204 });
      if (method === 'POST' && url.endsWith('/api-tokens')) {
        return json(
          {
            id: 't3',
            name: 'Neues Token',
            prefix: 'bc_new_0001_',
            token: 'bc_new_0001_geheim',
            scopes: ['documents:write'],
            rateLimitPerMinute: 60,
            createdAt: '2026-10-27T10:00:00Z',
            warning: 'Dieser Token wird nur einmal angezeigt.',
          },
          201,
        );
      }
      if (method === 'GET' && url.endsWith('/api-tokens')) {
        return json({ items: tokens, count: tokens.length, availableScopes });
      }
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

const MANAGER = user(['api_tokens.manage']);
const EMPLOYEE = user(['tasks.view']);

describe('ApiTokensPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    calls.length = 0;
  });

  it('lists the tokens with their beginning, scopes and state, but never their secret', async () => {
    serve();
    renderWithProviders(
      <SessionProvider user={MANAGER}>
        <ApiTokensPage />
      </SessionProvider>,
    );

    expect(await screen.findByText('Desktop-Bridge Empfang')).toBeInTheDocument();
    expect(screen.getByText('Alter Scanner')).toBeInTheDocument();
    expect(screen.getByText('bc_12345678_…')).toBeInTheDocument();
    expect(screen.getByText('Dokumente einliefern')).toBeInTheDocument();
    expect(screen.getByText('Dokumente lesen')).toBeInTheDocument();
    expect(screen.getByText('Aktiv')).toBeInTheDocument();
    // Once as the state of the revoked token, once as the action on the active one.
    expect(screen.getAllByText('Widerrufen').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Nie verwendet')).toBeInTheDocument();
    // The list only ever names a token: no secret in the DOM, whatever the state of the list.
    expect(document.body.textContent).not.toContain('geheim');
    expect(
      calls.some((call) => call.method === 'GET' && call.url === '/api/v1/api-tokens'),
    ).toBe(true);
  });

  it('creates a token with the chosen scopes and shows the value exactly once', async () => {
    serve();
    // jsdom exposes no clipboard; the app also works with the async API, so a stub is enough here.
    // `userEvent.setup()` installs its own stub as well — either way the copy path must succeed.
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      configurable: true,
    });
    const person = userEvent.setup();

    renderWithProviders(
      <SessionProvider user={MANAGER}>
        <ApiTokensPage />
      </SessionProvider>,
    );
    await screen.findByText('Desktop-Bridge Empfang');

    await person.click(screen.getByRole('button', { name: 'Token anlegen' }));
    const form = screen.getByRole('dialog', { name: 'Neues API-Token' });
    await person.type(within(form).getByLabelText('Name'), 'Partner A');
    await person.click(within(form).getByRole('checkbox', { name: /Dokumente einliefern/ }));
    await person.click(within(form).getByRole('button', { name: 'Token erstellen' }));

    await waitFor(() =>
      expect(
        calls.some((call) => {
          if (call.method !== 'POST' || call.url !== '/api/v1/api-tokens' || !call.body) return false;
          const body = JSON.parse(call.body) as { name: string; scopes: string[] };
          return body.name === 'Partner A' && body.scopes.join(',') === 'documents:write';
        }),
      ).toBe(true),
    );

    const created = await screen.findByRole('dialog', { name: 'Token angelegt' });
    expect(within(created).getByText('bc_new_0001_geheim')).toBeInTheDocument();
    expect(within(created).getByText('Nur einmal sichtbar')).toBeInTheDocument();

    await person.click(within(created).getByRole('button', { name: 'Token kopieren' }));
    expect(await screen.findByText('Token in die Zwischenablage kopiert.')).toBeInTheDocument();

    await person.click(within(created).getByRole('button', { name: 'Fertig' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Token angelegt' })).toBeNull());
    // The value is gone from the screen once the dialog is closed; only a reload remains.
    expect(document.body.textContent).not.toContain('bc_new_0001_geheim');
    expect(
      calls.filter((call) => call.method === 'GET' && call.url === '/api/v1/api-tokens').length,
    ).toBeGreaterThanOrEqual(2);
  });

  it('asks before revoking and then revokes exactly that token', async () => {
    serve();
    const person = userEvent.setup();

    renderWithProviders(
      <SessionProvider user={MANAGER}>
        <ApiTokensPage />
      </SessionProvider>,
    );
    await screen.findByText('Desktop-Bridge Empfang');

    await person.click(screen.getByRole('button', { name: 'Widerrufen' }));
    const confirm = screen.getByRole('dialog', { name: 'Token widerrufen?' });
    expect(within(confirm).getByText(/Desktop-Bridge Empfang/)).toBeInTheDocument();
    await person.click(within(confirm).getByRole('button', { name: 'Widerrufen' }));

    await waitFor(() =>
      expect(
        calls.some(
          (call) => call.method === 'POST' && call.url === '/api/v1/api-tokens/t1/revoke',
        ),
      ).toBe(true),
    );
    expect(await screen.findByText('Token widerrufen')).toBeInTheDocument();
  });

  it('refuses the screen to a person without api_tokens.manage and asks the server nothing', async () => {
    serve();
    renderWithProviders(
      <SessionProvider user={EMPLOYEE}>
        <ApiTokensPage />
      </SessionProvider>,
    );

    expect(screen.getByText('Kein Zugriff')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Token anlegen' })).toBeNull();
    expect(calls).toHaveLength(0);
  });
});
