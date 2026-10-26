import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, within } from '@/test/render';
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
  usePathname: () => '/cases',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

describe('screen registry', () => {
  it('covers every screen the backend serves, and no screen for a removed cabinet kind', () => {
    // The registry holds the screens the backend still serves: the removed contracts, radar, assistant,
    // payments and family/company/employees modules are gone, and the generated letters remain one screen.
    // The count is asserted on purpose: a screen added to the registry without appearing here is a screen the
    // product claims and does not have.
    expect(screens).toHaveLength(41);
    expect(new Set(screens.map((screen) => screen.id)).size).toBe(42);
    expect(new Set(screens.map((screen) => screen.href)).size).toBe(42);
    const ids = screens.map((screen) => screen.id);
    expect(ids).not.toContain('family');
    expect(ids).not.toContain('company');
    expect(ids).not.toContain('employees');
  });

  it('puts every workspace screen into a sidebar section', () => {
    const sectionIds = new Set(navSections.map((section) => section.id));
    for (const screen of screens) {
      if (screen.section !== null) {
        expect(sectionIds.has(screen.section)).toBe(true);
      }
    }
    // Three groups and no more than twelve items - the specification's acceptance criterion (§11.3). A fourth
    // group or a thirteenth item is a regression, not a feature.
    expect(sidebarScreens).toHaveLength(11);
    expect(navSections).toHaveLength(3);
    for (const section of navSections) {
      expect(sidebarScreens.some((screen) => screen.section === section.id)).toBe(true);
    }
  });

  it('keeps the Kanzlei menu at three groups and at most twelve items', () => {
    // Guardian against the menu growing back: the specification forbids more, and a hidden module is a menu
    // that lies about what the product does.
    expect(navSections.length).toBeLessThanOrEqual(3);
    expect(sidebarScreens.length).toBeLessThanOrEqual(12);
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
    expect(findScreenByPath('/')?.id).toBe('landing');
  });

  it('exposes screens by id', () => {
    expect(getScreen('audit').href).toBe('/audit');
    expect(() => getScreen('nope' as never)).toThrow();
  });
});

describe('plans, permissions and the menu', () => {
  // There is one kind of cabinet now - the Kanzlei - so the menu filter answers two questions only: what the
  // plan includes, and what the server granted. The mechanism stays tested against the whole registry.
  const names = (user: Parameters<typeof screensVisibleFor>[1]): string[] =>
    screensVisibleFor(screens, user).map((item) => item.id);
  const plan = (planTier: number) => ({ planTier });

  it('hides what the plan does not include', () => {
    const free = names({ subscription: plan(0) });
    expect(free).toContain('documents');
    expect(free).not.toContain('automations');
    expect(free).not.toContain('invoices');

    const business = names({ subscription: plan(2) });
    expect(business).toContain('invoices');
    expect(business).toContain('automations');
    expect(business).not.toContain('audit');

    const professional = names({ subscription: plan(3) });
    expect(professional).toContain('audit');
  });

  it('hides nothing while the plan is unknown (the server decides)', () => {
    expect(names({})).toContain('invoices');
  });

  it('has no screen for a family, a company cockpit or employees: the cabinet is one', () => {
    // §4.3: the family and company modules are deleted, not hidden. A screen that came back would be a menu
    // that lies about what the product does.
    const ids = screens.map((item) => item.id);
    expect(ids).not.toContain('family');
    expect(ids).not.toContain('company');
    expect(ids).not.toContain('employees');
    // parties was merged into contacts and removed (§4.3) as well.
    expect(ids).not.toContain('parties');
  });

  it('shows the admin area only to the administrator', () => {
    expect(names({})).not.toContain('admin');
    expect(names({ platformAdmin: true })).toContain('admin');
  });

  it('keeps the date vocabulary reachable through Einstellungen, under settings.view', () => {
    // The Kanzlei menu has eleven items; the vocabulary lives inside Einstellungen, not beside it.
    expect(sidebarScreens.map((item) => item.id)).not.toContain('dateMarkers');
    expect(getScreen('dateMarkers').href).toBe('/settings/date-markers');
    // It remains a registered screen, and its permission still decides whether the server would offer it.
    expect(names({ permissions: ['tasks.view'] })).not.toContain('dateMarkers');
    expect(names({ permissions: ['settings.view'] })).toContain('dateMarkers');
  });

  it('keeps the account mapping reachable through Einstellungen, under settings.view', () => {
    // Without a row in the mapping the DATEV export writes no bookings, so the screen must be reachable even
    // though it is not one of the eleven menu items.
    expect(sidebarScreens.map((item) => item.id)).not.toContain('kontoMapping');
    expect(getScreen('kontoMapping').href).toBe('/settings/konto-mapping');
    expect(names({ permissions: ['tasks.view'] })).not.toContain('kontoMapping');
    expect(names({ permissions: ['settings.view'] })).toContain('kontoMapping');
  });
});

describe('Sidebar', () => {
  it('renders a labelled navigation landmark with all sections and screens', () => {
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    // Eleven menu items plus the brand link back to the dashboard. The number is the specification's, not a
    // snapshot: the menu was cut from thirty-five to ten on purpose.
    expect(within(nav).getAllByRole('link')).toHaveLength(12);

    for (const section of navSections) {
      // "Mandanten" is both a group and an item, so the group is identified as the list it labels, not
      // by its text alone.
      expect(
        within(nav).getByRole('list', { name: catalogues.de.nav[section.labelKey] }),
      ).toBeInTheDocument();
    }
    for (const screen of screensVisibleFor(sidebarScreens, null)) {
      expect(
        within(nav).getByRole('link', { name: catalogues.de.nav[screen.titleKey] }),
      ).toBeInTheDocument();
    }
    expect(within(nav).queryByRole('link', { name: catalogues.de.nav['items.admin'] })).toBeNull();
  });

  it('marks the active screen with aria-current', () => {
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    const active = within(nav).getByRole('link', { name: catalogues.de.nav['items.cases'] });
    expect(active).toHaveAttribute('aria-current', 'page');
  });

  it('keeps link names accessible when collapsed', () => {
    renderWithProviders(<Sidebar collapsed onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    expect(
      within(nav).getByRole('link', { name: catalogues.de.nav['items.cases'] }),
    ).toBeInTheDocument();
  });

  it('translates titles when the locale is switched', () => {
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />, {
      locale: 'ru',
    });

    const nav = screen.getByRole('navigation', { name: 'Основная навигация' });
    expect(
      within(nav).getByRole('link', { name: catalogues.ru.nav['items.documents'] }),
    ).toBeInTheDocument();
  });

  it('keeps the date vocabulary out of the menu, whichever permissions were granted', () => {
    const user = {
      id: 'u',
      name: 'N',
      email: 'n@example.de',
      roles: [],
      workspaceId: 'w',
      workspaceName: 'W',
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

    renderWithProviders(
      <SessionProvider user={{ ...user, permissions: ['tasks.view', 'settings.view'] }}>
        <Sidebar collapsed={false} onToggleCollapse={() => undefined} />
      </SessionProvider>,
    );
    // Even with the permission the vocabulary stays inside Einstellungen, not in the Kanzlei menu.
    expect(
      within(screen.getByRole('navigation', { name: 'Hauptnavigation' })).queryByRole('link', {
        name: catalogues.de.nav['items.dateMarkers'],
      }),
    ).toBeNull();
  });
});
