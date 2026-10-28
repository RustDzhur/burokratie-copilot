import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, within } from '@/test/render';
import { Sidebar } from '@/components/app/sidebar';
import {
  navSections,
  screens,
  screensVisibleFor,
  sidebarScreens,
  findScreenByPath,
  getScreen,
} from '../screens';
import { catalogues } from '@/i18n/messages';
import { SessionProvider } from '@/lib/auth/session-context';

vi.mock('next/navigation', () => ({
  usePathname: () => '/inbox',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

describe('screen registry', () => {
  it('covers all screens the product navigates to', () => {
    // The retired screens (assistant, automations, scan) left the registry: their routes redirect
    // and their pages stay on disk only until they are removed. The count is asserted on purpose:
    // a screen added to the registry without appearing here is a screen the product claims and
    // does not have.
    expect(screens).toHaveLength(44);
    expect(new Set(screens.map((screen) => screen.id)).size).toBe(44);
    expect(new Set(screens.map((screen) => screen.href)).size).toBe(44);
  });

  it('puts every workspace screen into a sidebar section', () => {
    const sectionIds = new Set(navSections.map((section) => section.id));
    for (const screen of screens) {
      if (screen.section !== null) {
        expect(sectionIds.has(screen.section)).toBe(true);
      }
    }
    expect(sidebarScreens).toHaveLength(34);
  });

  it('has a German, English and Russian title for every screen', () => {
    for (const screen of screens) {
      for (const locale of ['de', 'en', 'ru'] as const) {
        const value = catalogues[locale].nav[screen.titleKey];
        expect(value, `${locale}:${screen.titleKey}`).toBeTruthy();
      }
    }
  });

  it('uses absolute paths starting with a slash', () => {
    for (const screen of screens) {
      expect(screen.href.startsWith('/')).toBe(true);
    }
  });

  it('resolves the active screen from a pathname, preferring the longest match', () => {
    expect(findScreenByPath('/dashboard')?.id).toBe('dashboard');
    expect(findScreenByPath('/dashboard/')?.id).toBe('dashboard');
    expect(findScreenByPath('/documents/abc-123')?.id).toBe('documents');
    expect(findScreenByPath('/unknown-route')).toBeUndefined();
    expect(findScreenByPath('/')).toBeUndefined(); // корень ведёт на /login
  });

  it('exposes screens by id', () => {
    expect(getScreen('audit').href).toBe('/audit');
    expect(() => getScreen('nope' as never)).toThrow();
  });
});

describe('plans and the menu', () => {
  const names = (user: Parameters<typeof screensVisibleFor>[1]): string[] =>
    screensVisibleFor(sidebarScreens, user).map((item) => item.id);
  const plan = (planTier: number) => ({ planTier });

  it('hides what the plan does not include, and what the cabinet does not need', () => {
    const free = names({ workspaceType: 'company', subscription: plan(0) });
    expect(free).toContain('documents');
    expect(free).not.toContain('contracts');
    expect(free).not.toContain('invoices');

    const business = names({ workspaceType: 'company', subscription: plan(2) });
    expect(business).toContain('invoices');
    expect(business).toContain('contracts');
    expect(business).not.toContain('employees');
    expect(business).not.toContain('audit');

    const professional = names({ workspaceType: 'company', subscription: plan(3) });
    expect(professional).toContain('employees');
    expect(professional).toContain('audit');

    // A family never sees the business side, whatever it pays for.
    const family = names({ workspaceType: 'family', subscription: plan(3) });
    expect(family).toContain('family');
    expect(family).not.toContain('invoices');
    expect(family).not.toContain('payments');
    expect(family).not.toContain('company');
    expect(family).not.toContain('employees');
  });

  it('hides nothing while the plan is unknown (the server decides)', () => {
    expect(names({ workspaceType: 'company' })).toContain('invoices');
  });

  it('shows the date vocabulary only where the server granted settings.view', () => {
    expect(names({ workspaceType: 'private', permissions: ['settings.view'] })).toContain(
      'dateMarkers',
    );
    expect(names({ workspaceType: 'private', permissions: ['tasks.view'] })).not.toContain(
      'dateMarkers',
    );
    // An unknown permission list hides nothing: the server decides and refuses the call instead.
    expect(names({ workspaceType: 'private' })).toContain('dateMarkers');
  });

  it('shows the constellations when either reading permission was granted', () => {
    // The backend accepts agents.view or constellations.view for GET /constellations; the menu
    // follows the same rule instead of inventing a third one.
    expect(names({ workspaceType: 'private', permissions: ['constellations.view'] })).toContain(
      'constellations',
    );
    expect(names({ workspaceType: 'private', permissions: ['agents.view'] })).toContain(
      'constellations',
    );
    expect(names({ workspaceType: 'private', permissions: ['tasks.view'] })).not.toContain(
      'constellations',
    );
    // An unknown permission list hides nothing here either.
    expect(names({ workspaceType: 'private' })).toContain('constellations');
  });
});

describe('Sidebar', () => {
  it('renders a labelled navigation landmark: daily screens up front, everything else in "Weitere"', async () => {
    const person = userEvent.setup();
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    // The five daily screens plus the brand link back to the dashboard.
    expect(within(nav).getAllByRole('link')).toHaveLength(6);
    for (const id of ['dashboard', 'inbox', 'documents', 'tasks', 'calendar'] as const) {
      expect(
        within(nav).getByRole('link', { name: catalogues.de.nav[`items.${id}`] }),
      ).toBeInTheDocument();
    }

    // Everything else waits, grouped by section, in the collapsed "Weitere" block.
    const toggle = within(nav).getByRole('button', { name: /Weitere/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await person.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    for (const section of navSections) {
      expect(within(nav).getByText(catalogues.de.nav[section.labelKey])).toBeInTheDocument();
    }
    for (const screen of screensVisibleFor(sidebarScreens, null)) {
      expect(
        within(nav).getByRole('link', { name: catalogues.de.nav[screen.titleKey] }),
      ).toBeInTheDocument();
    }
    expect(within(nav).queryByRole('link', { name: catalogues.de.nav['items.admin'] })).toBeNull();
  });

  it('shows each kind of workspace its own screens, and the admin area only to the administrator', () => {
    const base = { id: 'u', name: 'N', email: 'n@example.de', roles: [], workspaceId: 'w', workspaceName: 'W' } as const;
    const names = (user: Parameters<typeof screensVisibleFor>[1]): string[] =>
      screensVisibleFor(sidebarScreens, user).map((item) => item.id);

    expect(names({ workspaceType: 'private' })).not.toContain('family');
    expect(names({ workspaceType: 'private' })).not.toContain('company');
    expect(names({ workspaceType: 'family' })).toContain('family');
    expect(names({ workspaceType: 'family' })).not.toContain('employees');
    expect(names({ workspaceType: 'company' })).toContain('employees');
    expect(names({ workspaceType: 'company' })).not.toContain('family');
    expect(names({ workspaceType: 'private' })).not.toContain('invoices');
    expect(names({ workspaceType: 'company' })).toContain('invoices');
    for (const kind of ['private', 'family', 'company'] as const) {
      expect(names({ workspaceType: kind })).toContain('parties');
    }
    expect(names({ workspaceType: 'company' })).not.toContain('admin');
    expect(names({ workspaceType: 'company', platformAdmin: true })).toContain('admin');
    expect(base.id).toBe('u');
  });

  it('marks the active screen with aria-current', () => {
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    const active = within(nav).getByRole('link', { name: 'Eingang' });
    expect(active).toHaveAttribute('aria-current', 'page');
  });

  it('keeps link names accessible when collapsed', () => {
    renderWithProviders(<Sidebar collapsed onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    expect(within(nav).getByRole('link', { name: 'Eingang' })).toBeInTheDocument();
  });

  it('translates titles when the locale is switched', () => {
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />, {
      locale: 'ru',
    });

    const nav = screen.getByRole('navigation', { name: 'Основная навигация' });
    expect(within(nav).getByRole('link', { name: 'Входящие' })).toBeInTheDocument();
  });

  it('leaves the date vocabulary out of the menu for a person without settings.view', async () => {
    const user = {
      id: 'u',
      name: 'N',
      email: 'n@example.de',
      roles: [],
      workspaceId: 'w',
      workspaceName: 'W',
      workspaceType: 'private' as const,
      permissions: ['tasks.view'],
    };
    const { unmount } = renderWithProviders(
      <SessionProvider user={user}>
        <Sidebar collapsed={false} onToggleCollapse={() => undefined} />
      </SessionProvider>,
    );
    expect(
      within(screen.getByRole('navigation', { name: 'Hauptnavigation' })).queryByRole('link', {
        name: catalogues.de.nav['items.dateMarkers'],
      }),
    ).toBeNull();
    unmount();

    const person = userEvent.setup();
    renderWithProviders(
      <SessionProvider user={{ ...user, permissions: ['tasks.view', 'settings.view'] }}>
        <Sidebar collapsed={false} onToggleCollapse={() => undefined} />
      </SessionProvider>,
    );
    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    // The date vocabulary waits with the other settings screens in "Weitere".
    // ("Weitere" may already be open: the sidebar remembers that choice per browser.)
    const toggle = within(nav).getByRole('button', { name: /Weitere/ });
    if (toggle.getAttribute('aria-expanded') === 'false') await person.click(toggle);
    expect(
      within(nav).getByRole('link', { name: catalogues.de.nav['items.dateMarkers'] }),
    ).toHaveAttribute('href', '/settings/date-markers');
  });
});
