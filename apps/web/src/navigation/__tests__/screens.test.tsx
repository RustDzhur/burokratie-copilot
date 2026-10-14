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

vi.mock('next/navigation', () => ({
  usePathname: () => '/cases',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

describe('screen registry', () => {
  it('covers all 30 screens required by the specification, and the people screen of the cabinets', () => {
    // §5.2.2 (registration and sign-in) is four routes, hence 33 in total.
    expect(screens).toHaveLength(39);
    expect(new Set(screens.map((screen) => screen.id)).size).toBe(39);
    expect(new Set(screens.map((screen) => screen.href)).size).toBe(39);
  });

  it('puts every workspace screen into a sidebar section', () => {
    const sectionIds = new Set(navSections.map((section) => section.id));
    for (const screen of screens) {
      if (screen.section !== null) {
        expect(sectionIds.has(screen.section)).toBe(true);
      }
    }
    expect(sidebarScreens).toHaveLength(31);
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
});

describe('Sidebar', () => {
  it('renders a labelled navigation landmark with all sections and screens', () => {
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    // 27 workspace screens (the admin area is hidden from everybody but the platform administrator) plus the brand link back to the dashboard.
    expect(within(nav).getAllByRole('link')).toHaveLength(31);

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
    const active = within(nav).getByRole('link', { name: 'Vorgänge und Akten' });
    expect(active).toHaveAttribute('aria-current', 'page');
  });

  it('keeps link names accessible when collapsed', () => {
    renderWithProviders(<Sidebar collapsed onToggleCollapse={() => undefined} />);

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    expect(within(nav).getByRole('link', { name: 'Vorgänge und Akten' })).toBeInTheDocument();
  });

  it('translates titles when the locale is switched', () => {
    renderWithProviders(<Sidebar collapsed={false} onToggleCollapse={() => undefined} />, {
      locale: 'ru',
    });

    const nav = screen.getByRole('navigation', { name: 'Основная навигация' });
    expect(within(nav).getByRole('link', { name: 'Входящие' })).toBeInTheDocument();
  });
});
