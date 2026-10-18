import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fireEvent,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  within,
} from '@/test/render';
import { SessionProvider } from '@/lib/auth/session-context';
import type { SessionUser } from '@/lib/api/types';
import { screensVisibleFor, sidebarScreens } from '@/navigation/screens';
import { catalogues } from '@/i18n/messages';
import { Sidebar } from '../sidebar';

vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

interface Call {
  readonly method: string;
  readonly url: string;
  readonly body: unknown;
}

type StoredMenu = { order: string[]; hidden: string[]; updatedAt: string | null };

let calls: Call[] = [];
let stored: StoredMenu = { order: [], hidden: [], updatedAt: null };
let putFails = false;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * Serves the navigation endpoint and swallows the CSRF bootstrap the client performs before unsafe
 * methods. Everything else is recorded, so a test can assert both the rendered menu and the request.
 */
function serveNavigation(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      const body =
        typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
      calls.push({ method, url, body });
      if (!url.endsWith('/me/navigation')) return json({});
      if (method === 'GET') return json(stored);
      if (method === 'PUT') {
        if (putFails) return json({ code: 'UPSTREAM' }, 500);
        const next = body as { order: string[]; hidden: string[] };
        stored = { order: next.order, hidden: next.hidden, updatedAt: '2026-10-18T20:00:00Z' };
        return json(stored);
      }
      if (method === 'DELETE') {
        stored = { order: [], hidden: [], updatedAt: null };
        return new Response(null, { status: 204 });
      }
      return json({});
    }),
  );
}

const privateUser: SessionUser = {
  id: 'u1',
  name: 'Erika Weber',
  email: 'erika@example.de',
  roles: [],
  workspaceId: 'w1',
  workspaceName: 'Weber',
  workspaceType: 'private',
};

function renderSidebar(user: SessionUser = privateUser): ReturnType<typeof renderWithProviders> {
  return renderWithProviders(
    <SessionProvider user={user}>
      <Sidebar collapsed={false} onToggleCollapse={() => undefined} />
    </SessionProvider>,
  );
}

const nav = (): HTMLElement => screen.getByRole('navigation', { name: 'Hauptnavigation' });
const navLinks = (): HTMLElement[] => within(nav()).getAllByRole('link');
/** Only the entries of the main list, without the brand link and without "Weitere". */
const mainLinks = (): HTMLElement[] =>
  within(screen.getByTestId('nav-main')).getAllByRole('link');

async function openEditMode(): Promise<void> {
  const person = userEvent.setup();
  await person.click(within(nav()).getByRole('button', { name: 'Anpassen' }));
}

function findPut(): Call | undefined {
  return calls.find((call) => call.method === 'PUT' && call.url.endsWith('/me/navigation'));
}

describe('personal sidebar', () => {
  beforeEach(() => {
    window.localStorage.clear();
    calls = [];
    stored = { order: [], hidden: [], updatedAt: null };
    putFails = false;
    serveNavigation();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows the standard menu by sections while nothing is stored', async () => {
    renderSidebar();
    await waitFor(() =>
      expect(calls.some((call) => call.method === 'GET' && call.url.endsWith('/me/navigation'))).toBe(
        true,
      ),
    );

    const expected = screensVisibleFor(sidebarScreens, privateUser);
    expect(navLinks()).toHaveLength(expected.length + 1);
    for (const item of expected) {
      expect(
        within(nav()).getByRole('link', { name: catalogues.de.nav[item.titleKey] }),
      ).toBeInTheDocument();
    }
    // The familiar section headings are untouched, and there is nothing to put away.
    expect(within(nav()).getByText('Arbeitsbereich')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull();
  });

  it('applies the stored order without section headings', async () => {
    stored = { order: ['inbox', 'documents'], hidden: [], updatedAt: '2026-10-18T20:00:00Z' };
    renderSidebar();

    await waitFor(() => {
      expect(mainLinks()[0]).toHaveAttribute('href', '/inbox');
      expect(mainLinks()[1]).toHaveAttribute('href', '/documents');
    });
    expect(within(nav()).queryByText('Arbeitsbereich')).toBeNull();
    // Nothing fell out of the menu: the unplaced screens follow the stored ones.
    expect(navLinks()).toHaveLength(screensVisibleFor(sidebarScreens, privateUser).length + 1);
  });

  it('moves a hidden screen into "Weitere" and shows it again at the end', async () => {
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    await person.click(within(nav()).getByRole('button', { name: 'Dashboard ausblenden' }));

    // In edit mode "Weitere" opens itself, because that is where the restore button lives.
    const toggle = within(nav()).getByRole('button', { name: /Weitere/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const region = document.getElementById('nav-weitere');
    expect(region).not.toBeNull();
    expect(
      within(region as HTMLElement).getByRole('link', {
        name: catalogues.de.nav['items.dashboard'],
      }),
    ).toBeInTheDocument();
    // The dashboard is no longer part of the main list.
    expect(mainLinks().map((link) => link.getAttribute('href'))).not.toContain('/dashboard');

    await person.click(
      within(region as HTMLElement).getByRole('button', { name: 'Dashboard wieder anzeigen' }),
    );

    await waitFor(() => expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull());
    expect(mainLinks()[mainLinks().length - 1]).toHaveAttribute('href', '/dashboard');
  });

  it('persists a change once, after the short pause, with the complete menu', async () => {
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    const expected = screensVisibleFor(sidebarScreens, privateUser);
    const person = userEvent.setup();
    await person.click(within(nav()).getByRole('button', { name: 'Dashboard ausblenden' }));

    await waitFor(() => expect(findPut()).toBeDefined(), { timeout: 3000 });
    const body = findPut()?.body as { order: string[]; hidden: string[] };
    expect(body.hidden).toEqual(['dashboard']);
    expect(body.order).not.toContain('dashboard');
    expect(body.order).toHaveLength(expected.length - 1);
    // The order carries every remaining screen, so hiding something alone cannot lose the others.
    expect(new Set(body.order)).toEqual(
      new Set(expected.map((item) => item.id).filter((id) => id !== 'dashboard')),
    );
  });

  it('reorders with the up/down buttons, not only by dragging', async () => {
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    await person.click(
      within(nav()).getByRole('button', { name: 'Dashboard nach unten verschieben' }),
    );

    expect(mainLinks()[0]).toHaveAttribute('href', '/inbox');
    expect(mainLinks()[1]).toHaveAttribute('href', '/dashboard');
    // The move is announced for screen readers.
    expect(screen.getByText('Dashboard steht jetzt weiter unten.')).toBeInTheDocument();
  });

  it('reorders by dragging one entry onto another', async () => {
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    const dashboard = within(nav())
      .getByRole('link', { name: catalogues.de.nav['items.dashboard'] })
      .closest('li') as HTMLElement;
    const inbox = within(nav())
      .getByRole('link', { name: catalogues.de.nav['items.inbox'] })
      .closest('li') as HTMLElement;
    const transfer = {
      setData: vi.fn(),
      getData: () => 'dashboard',
      effectAllowed: 'move',
      dropEffect: 'none',
    };

    fireEvent.dragStart(dashboard, { dataTransfer: transfer });
    fireEvent.dragOver(inbox, { dataTransfer: transfer, clientY: 10 });
    fireEvent.drop(inbox, { dataTransfer: transfer });

    await waitFor(() => {
      expect(mainLinks()[0]).toHaveAttribute('href', '/inbox');
      expect(mainLinks()[1]).toHaveAttribute('href', '/dashboard');
    });
  });

  it('resets to the standard menu after an explicit confirmation', async () => {
    stored = { order: ['inbox', 'dashboard'], hidden: ['audit'], updatedAt: '2026-10-18T20:00:00Z' };
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks()[0]).toHaveAttribute('href', '/inbox'));

    await openEditMode();
    await person.click(within(nav()).getByRole('button', { name: 'Auf Standard zurücksetzen' }));

    const dialog = await screen.findByRole('dialog');
    await person.click(within(dialog).getByRole('button', { name: 'Zurücksetzen' }));

    await waitFor(() =>
      expect(
        calls.some((call) => call.method === 'DELETE' && call.url.endsWith('/me/navigation')),
      ).toBe(true),
    );
    await waitFor(() => expect(within(nav()).getByText('Arbeitsbereich')).toBeInTheDocument());
    expect(
      within(nav()).getByRole('link', { name: catalogues.de.nav['items.audit'] }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull();
  });

  it('rolls the menu back and says so when the server refuses the change', async () => {
    putFails = true;
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    await person.click(within(nav()).getByRole('button', { name: 'Dashboard ausblenden' }));

    await waitFor(() => expect(findPut()).toBeDefined(), { timeout: 3000 });
    await waitFor(() => expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull());
    expect(
      within(nav()).getByRole('link', { name: catalogues.de.nav['items.dashboard'] }),
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Ihre Menüänderung wurde nicht gespeichert.'),
    ).toBeInTheDocument();
  });

  it('never shows a stored screen the person may not open', async () => {
    stored = { order: ['dateMarkers', 'dashboard'], hidden: [], updatedAt: null };
    renderSidebar({ ...privateUser, permissions: ['tasks.view'] });

    await waitFor(() => expect(mainLinks()[0]).toHaveAttribute('href', '/dashboard'));
    expect(
      within(nav()).queryByRole('link', { name: catalogues.de.nav['items.dateMarkers'] }),
    ).toBeNull();
  });

  it('remembers whether "Weitere" was left open in this browser', async () => {
    stored = { order: [], hidden: ['audit'], updatedAt: null };
    const person = userEvent.setup();
    const first = renderSidebar();

    const toggle = await screen.findByRole('button', { name: /Weitere/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(document.getElementById('nav-weitere')).toBeNull();

    await person.click(toggle);
    expect(within(nav()).getByRole('link', { name: catalogues.de.nav['items.audit'] })).toBeInTheDocument();
    expect(window.localStorage.getItem('bc.sidebar.weitere')).toBe('open');
    first.unmount();

    renderSidebar();
    const reopened = await screen.findByRole('button', { name: /Weitere/ });
    await waitFor(() => expect(reopened).toHaveAttribute('aria-expanded', 'true'));
  });
});
