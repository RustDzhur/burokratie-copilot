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
import { navSections, screensVisibleFor, sidebarScreens } from '@/navigation/screens';
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

const companyUser: SessionUser = {
  id: 'u1',
  name: 'Erika Weber',
  email: 'erika@example.de',
  roles: [],
  workspaceId: 'w1',
  workspaceName: 'Weber & Partner',
};

/** A person the server granted no permission: "Exporte" needs documents.export, so the menu must hide it. */
const limitedUser: SessionUser = { ...companyUser, id: 'u2', permissions: [] };

function renderSidebar(user: SessionUser = companyUser): ReturnType<typeof renderWithProviders> {
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
const mainHrefs = (): (string | null)[] =>
  mainLinks().map((link) => link.getAttribute('href'));

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

    const expected = screensVisibleFor(sidebarScreens, companyUser);
    expect(expected).toHaveLength(11);
    expect(navLinks()).toHaveLength(expected.length + 1);
    for (const item of expected) {
      expect(
        within(nav()).getByRole('link', { name: catalogues.de.nav[item.titleKey] }),
      ).toBeInTheDocument();
    }
    // The three Kanzlei groups are still the product's suggestion, and there is nothing to put away.
    for (const section of navSections) {
      expect(
        within(nav()).getByRole('list', { name: catalogues.de.nav[section.labelKey] }),
      ).toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull();
  });

  it('applies the stored order without section headings', async () => {
    stored = { order: ['cases', 'documents'], hidden: [], updatedAt: '2026-10-18T20:00:00Z' };
    renderSidebar();

    await waitFor(() => {
      expect(mainLinks()[0]).toHaveAttribute('href', '/cases');
      expect(mainLinks()[1]).toHaveAttribute('href', '/documents');
    });
    expect(within(nav()).queryByRole('list', { name: 'Arbeit' })).toBeNull();
    // Nothing fell out of the menu: the unplaced screens follow the stored ones.
    expect(navLinks()).toHaveLength(screensVisibleFor(sidebarScreens, companyUser).length + 1);
  });

  it('moves a hidden screen into "Weitere" and shows it again at the end', async () => {
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    await person.click(within(nav()).getByRole('button', { name: 'Posteingang ausblenden' }));

    // In edit mode "Weitere" opens itself, because that is where the restore button lives.
    const toggle = within(nav()).getByRole('button', { name: /Weitere/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const region = document.getElementById('nav-weitere');
    expect(region).not.toBeNull();
    expect(
      within(region as HTMLElement).getByRole('link', {
        name: catalogues.de.nav['items.documents'],
      }),
    ).toBeInTheDocument();
    // The inbox screen is no longer part of the main list.
    expect(mainHrefs()).not.toContain('/documents');

    await person.click(
      within(region as HTMLElement).getByRole('button', { name: 'Posteingang wieder anzeigen' }),
    );

    await waitFor(() => expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull());
    expect(mainHrefs()[mainHrefs().length - 1]).toBe('/documents');
  });

  it('persists a change once, after the short pause, with the complete menu', async () => {
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    const expected = screensVisibleFor(sidebarScreens, companyUser);
    const person = userEvent.setup();
    await person.click(within(nav()).getByRole('button', { name: 'Posteingang ausblenden' }));

    await waitFor(() => expect(findPut()).toBeDefined(), { timeout: 3000 });
    const body = findPut()?.body as { order: string[]; hidden: string[] };
    expect(body.hidden).toEqual(['documents']);
    expect(body.order).not.toContain('documents');
    expect(body.order).toHaveLength(expected.length - 1);
    // The order carries every remaining screen, so hiding something alone cannot lose the others.
    expect(new Set(body.order)).toEqual(
      new Set(expected.map((item) => item.id).filter((id) => id !== 'documents')),
    );
  });

  it('reorders with the up/down buttons, not only by dragging', async () => {
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    await person.click(
      within(nav()).getByRole('button', { name: 'Posteingang nach unten verschieben' }),
    );

    expect(mainHrefs()[0]).toBe('/cases');
    expect(mainHrefs()[1]).toBe('/documents');
    // The move is announced for screen readers.
    expect(screen.getByText('Posteingang steht jetzt weiter unten.')).toBeInTheDocument();
  });

  it('reorders by dragging one entry onto another', async () => {
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    const inbox = within(nav())
      .getByRole('link', { name: catalogues.de.nav['items.documents'] })
      .closest('li') as HTMLElement;
    const cases = within(nav())
      .getByRole('link', { name: catalogues.de.nav['items.cases'] })
      .closest('li') as HTMLElement;
    const transfer = {
      setData: vi.fn(),
      getData: () => 'documents',
      effectAllowed: 'move',
      dropEffect: 'none',
    };

    fireEvent.dragStart(inbox, { dataTransfer: transfer });
    fireEvent.dragOver(cases, { dataTransfer: transfer, clientY: 10 });
    fireEvent.drop(cases, { dataTransfer: transfer });

    await waitFor(() => {
      expect(mainHrefs()[0]).toBe('/cases');
      expect(mainHrefs()[1]).toBe('/documents');
    });
  });

  it('resets to the standard menu after an explicit confirmation', async () => {
    stored = { order: ['cases', 'documents'], hidden: ['archive'], updatedAt: '2026-10-18T20:00:00Z' };
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks()[0]).toHaveAttribute('href', '/cases'));

    await openEditMode();
    await person.click(within(nav()).getByRole('button', { name: 'Auf Standard zurücksetzen' }));

    const dialog = await screen.findByRole('dialog');
    await person.click(within(dialog).getByRole('button', { name: 'Zurücksetzen' }));

    await waitFor(() =>
      expect(
        calls.some((call) => call.method === 'DELETE' && call.url.endsWith('/me/navigation')),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(within(nav()).getByRole('list', { name: 'Arbeit' })).toBeInTheDocument(),
    );
    expect(
      within(nav()).getByRole('link', { name: catalogues.de.nav['items.archive'] }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull();
  });

  it('rolls the menu back and says so when the server refuses the change', async () => {
    putFails = true;
    const person = userEvent.setup();
    renderSidebar();
    await waitFor(() => expect(mainLinks().length).toBeGreaterThan(0));

    await openEditMode();
    await person.click(within(nav()).getByRole('button', { name: 'Posteingang ausblenden' }));

    await waitFor(() => expect(findPut()).toBeDefined(), { timeout: 3000 });
    await waitFor(() => expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull());
    expect(
      within(nav()).getByRole('link', { name: catalogues.de.nav['items.documents'] }),
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Ihre Menüänderung wurde nicht gespeichert.'),
    ).toBeInTheDocument();
  });

  it('never shows a stored screen the person may not open', async () => {
    // "Exporte" needs documents.export; a stored order that names it must not put it on a menu without the right.
    stored = { order: ['exports', 'cases'], hidden: [], updatedAt: null };
    renderSidebar(limitedUser);

    await waitFor(() => expect(mainLinks()[0]).toHaveAttribute('href', '/cases'));
    expect(within(nav()).queryByRole('link', { name: catalogues.de.nav['items.exports'] })).toBeNull();
    expect(navLinks()).toHaveLength(screensVisibleFor(sidebarScreens, limitedUser).length + 1);
  });

  it('ignores stored ids of screens that are no longer part of the menu', async () => {
    // The Kanzlei menu has ten items; integrations and notifications live inside Einstellungen (§4.3).
    // A stored preference that names them must not bring them back into the sidebar.
    stored = { order: [], hidden: ['integrations', 'notifications'], updatedAt: null };
    renderSidebar();

    await waitFor(() =>
      expect(calls.some((call) => call.method === 'GET' && call.url.endsWith('/me/navigation'))).toBe(
        true,
      ),
    );
    expect(screen.queryByRole('button', { name: /Weitere/ })).toBeNull();
    expect(
      within(nav()).queryByRole('link', { name: catalogues.de.nav['items.integrations'] }),
    ).toBeNull();
    expect(
      within(nav()).queryByRole('link', { name: catalogues.de.nav['items.notifications'] }),
    ).toBeNull();
  });

  it('remembers whether "Weitere" was left open in this browser', async () => {
    stored = { order: [], hidden: ['archive'], updatedAt: null };
    const person = userEvent.setup();
    const first = renderSidebar();

    const toggle = await screen.findByRole('button', { name: /Weitere/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(document.getElementById('nav-weitere')).toBeNull();

    await person.click(toggle);
    expect(
      within(nav()).getByRole('link', { name: catalogues.de.nav['items.archive'] }),
    ).toBeInTheDocument();
    expect(window.localStorage.getItem('bc.sidebar.weitere')).toBe('open');
    first.unmount();

    renderSidebar();
    const reopened = await screen.findByRole('button', { name: /Weitere/ });
    await waitFor(() => expect(reopened).toHaveAttribute('aria-expanded', 'true'));
  });
});
