import type { IconName } from '@/components/icons';

/**
 * Canonical registry of every screen required by docs/task.md §5.2.
 *
 * The sidebar, breadcrumbs, command palette, page headers and the automated
 * navigation test all read from this single registry — a screen cannot be
 * "forgotten" in one place while existing in another.
 */

export type SectionId = 'arbeit' | 'mandanten' | 'verwaltung';

export interface NavSection {
  readonly id: SectionId;
  readonly labelKey:
    | 'sections.arbeit'
    | 'sections.mandanten'
    | 'sections.verwaltung';
}

/**
 * The Kanzlei edition: three groups, ten items (spec `tz-kanzlei-edition.md` §4, §11.3).
 *
 * <p>The menu answers the bookkeeper's three questions in the order they are asked: what came in, whose paperwork
 * it is, and what has to be kept. Nothing else belongs here - the specification forbids more than twelve items
 * and a hidden module is a menu that lies about what the product does, so the modules behind the removed items
 * are deleted rather than tucked away.</p>
 */
export const navSections: readonly NavSection[] = [
  { id: 'arbeit', labelKey: 'sections.arbeit' },
  { id: 'mandanten', labelKey: 'sections.mandanten' },
  { id: 'verwaltung', labelKey: 'sections.verwaltung' },
];

export type ScreenId =
  | 'landing'
  | 'login'
  | 'register'
  | 'verifyEmail'
  | 'resetPassword'
  | 'onboarding'
  | 'dashboard'
  | 'inbox'
  | 'documentViewer'
  | 'review'
  | 'documents'
  | 'cases'
  | 'tasks'
  | 'approvals'
  | 'rules'
  | 'calendar'
  | 'invoices' | 'generated-documents' | 'generatedDocument'
  | 'payments'
  | 'contacts'
  | 'mail'
  | 'templates'
  | 'automations'
  | 'integrations'
  | 'scan'
  | 'family'
  | 'company'
  | 'employees'
  | 'reports'
  | 'exports'
  | 'archive'
  | 'security'
  | 'accessibilitySettings'
  | 'dateMarkers'
  | 'kontoMapping'
  | 'notifications'
  | 'audit'
  | 'billing'
  | 'admin'
  | 'accessEnded'
  | 'profile'
  | 'accountSettings';

export type WorkspaceKind = 'private' | 'family' | 'company';

export interface ScreenDefinition {
  readonly id: ScreenId;
  /** Route path; `[id]` marks a dynamic segment. */
  readonly href: string;
  readonly icon: IconName;
  /** Sidebar section; `null` for public/auth screens. */
  readonly section: SectionId | null;
  readonly inSidebar: boolean;
  readonly requiresAuth: boolean;
  /** Key inside the `nav` namespace (single source for screen titles). */
  readonly titleKey: `items.${ScreenId}`;
  /** Workspace kinds this screen is meant for; absent = every kind. Hidden from the menu otherwise, never blocked. */
  readonly audience?: readonly WorkspaceKind[];
  /** Shown only to the platform administrator. */
  readonly adminOnly?: boolean;
  /**
   * One backend permission this screen needs (e.g. `settings.view`). The menu hides the screen when the
   * person's permissions are known and do not include it; an unknown permission list hides nothing.
   */
  readonly requiredPermission?: string;
  /** Lowest plan tier that includes the screen; absent = every plan. Mirrors what the server enforces. */
  readonly minTier?: number;
}

function screen(
  id: ScreenId,
  href: string,
  icon: IconName,
  section: SectionId | null,
  options: {
    readonly inSidebar?: boolean;
    readonly requiresAuth?: boolean;
    readonly audience?: readonly WorkspaceKind[];
    readonly adminOnly?: boolean;
    /** A backend permission the screen needs; see `ScreenDefinition.requiredPermission`. */
    readonly requiredPermission?: string;
    /** The lowest plan tier (0 Free, 1 Personal, 2 Business, 3 Professional) that includes the screen. */
    readonly minTier?: number;
  } = {},
): ScreenDefinition {
  const inSidebar = options.inSidebar ?? section !== null;
  return {
    id,
    href,
    icon,
    section,
    inSidebar,
    requiresAuth: options.requiresAuth ?? inSidebar,
    titleKey: `items.${id}`,
    audience: options.audience,
    adminOnly: options.adminOnly,
    requiredPermission: options.requiredPermission,
    minTier: options.minTier,
  };
}

export const screens: readonly ScreenDefinition[] = [
  // §5.2.1 — public landing page
  screen('landing', '/', 'home', null, { inSidebar: false, requiresAuth: false }),
  // §5.2.2 — registration and sign-in
  screen('login', '/login', 'lock', null, { inSidebar: false, requiresAuth: false }),
  screen('register', '/register', 'user', null, { inSidebar: false, requiresAuth: false }),
  screen('verifyEmail', '/verify-email', 'mail', null, { inSidebar: false, requiresAuth: false }),
  screen('resetPassword', '/reset-password', 'key', null, {
    inSidebar: false,
    requiresAuth: false,
  }),
  // §5.2.3 — initial account setup
  screen('onboarding', '/onboarding', 'sparkles', null, { inSidebar: false }),
  // §5.2.4 — dashboard
  screen('dashboard', '/dashboard', 'grid', null, { inSidebar: false }),
  // §5.2.5 — inbox
  screen('inbox', '/inbox', 'inbox', null, { inSidebar: false }),
  // §5.2.6 — universal document viewer (reached from registry/inbox)
  screen('documentViewer', '/documents/[id]', 'file-text', null, { inSidebar: false }),
  // §5.2.7 — review centre
  screen('review', '/review', 'check-square', null, { inSidebar: false }),
  // §5.2.8 — document registry
  screen('documents', '/documents', 'inbox', 'arbeit'),
  // §5.2.9 — cases / Akten / Fälle
  screen('cases', '/cases', 'briefcase', 'arbeit'),
  // §5.2.10 — tasks and workflows
  screen('tasks', '/tasks', 'check-circle', null, { inSidebar: false }),
  // approval centre: what waits for a person's decision, with the material
  screen('approvals', '/approvals', 'check-circle', 'arbeit', { minTier: 1 }),
  // §5.2.11 — calendar and deadlines
  screen('calendar', '/calendar', 'clock', 'arbeit'),
  // §5.2.13 — invoices and expenses
  screen('invoices', '/invoices', 'receipt', 'arbeit', { audience: ['company'], minTier: 2 }),
  // §5.2.14 — payments and reconciliation
  screen('payments', '/payments', 'credit-card', null, { minTier: 2, audience: ['company'], inSidebar: false }),
  // §5.2.15 — contacts and organisations
  screen('contacts', '/contacts', 'users', 'mandanten', { minTier: 0 }),
  // §5.2.16 — mail centre
  screen('mail', '/mail', 'mail', null, { minTier: 1, inSidebar: false }),
  // §5.2.17 — template editor
  screen('templates', '/templates', 'mail', 'mandanten', { minTier: 1 }),
  // §5.2.18 — the letters generated from those templates
  screen('generated-documents', '/generated-documents', 'file-text', null, { minTier: 1, inSidebar: false }),
  // §5.2.18a — one generated letter, with what stands in the way of sending it. A detail route, not a
  // destination of its own, so it stays out of the sidebar.
  screen('generatedDocument', '/generated-documents/[id]', 'file-text', null,
    { inSidebar: false, minTier: 1 }),
  // §5.2.19 — automations
  screen('automations', '/automations', 'zap', null, { minTier: 2, inSidebar: false }),
  // §5.2.20 — integrations
  screen('integrations', '/integrations', 'plug', null, { minTier: 1, inSidebar: false }),
  // §5.2.21 — scanning and import
  screen('scan', '/scan', 'printer', null, { minTier: 1, inSidebar: false }),
  // whom the cabinet belongs to: the person, the household members, the company
  // §5.2.22 — family cockpit
  screen('family', '/family', 'shield-heart', null, { audience: ['family'], minTier: 1, inSidebar: false }),
  // §5.2.23 — company workspace
  screen('company', '/company', 'building', null, { audience: ['company'], minTier: 2, inSidebar: false }),
  // §5.2.24 — employees and roles
  screen('employees', '/employees', 'user-cog', null, { audience: ['company'], minTier: 3, inSidebar: false }),
  // §5.2.25 — reports and analytics
  screen('reports', '/reports', 'bar-chart', 'verwaltung', { minTier: 1 }),
  // §5.6 — DATEV export packages. Not in the original §5.2 list; the Kanzlei specification makes it a P0
  // destination of its own. The menu item is visible to everyone the server would allow to export; an
  // unknown permission list hides nothing.
  screen('exports', '/exports', 'download', 'verwaltung', { requiredPermission: 'documents.export' }),
  // §5.2.26 — archive and retention
  screen('archive', '/archive', 'archive', 'verwaltung'),
  // §5.2.27 — security settings
  screen('security', '/security', 'shield', null, { inSidebar: false }),
  // §5.2.28 — accessibility settings
  screen('accessibilitySettings', '/accessibility', 'accessibility', null, { inSidebar: false }),
  // thresholds and switches of the Radar and of document recognition, with a version history
  screen('rules', '/rules', 'shield', null, { inSidebar: false }),
  // the words that explain a date (Frist, Termin, Zahlungsziel), editable by the workspace
  screen('dateMarkers', '/settings/date-markers', 'calendar', null, {
    requiredPermission: 'settings.view',
  }),
  // the accounts the DATEV export books a voucher to (§5.6); without a row the export stays empty
  screen('kontoMapping', '/settings/konto-mapping', 'table', null, {
    requiredPermission: 'settings.view',
  }),
  // §5.2.29 — notification centre
  screen('notifications', '/notifications', 'bell', null, { inSidebar: false }),
  // §5.2.30 — activity history and audit
  screen('audit', '/audit', 'clock', null, { minTier: 3, inSidebar: false }),
  // plan, payment and invoices of the workspace
  screen('billing', '/billing', 'credit-card', null, { inSidebar: false }),
  // the platform administrator's area (customers, prices, payments, global settings)
  screen('admin', '/admin', 'shield', null, { adminOnly: true, inSidebar: false }),
  // the person's own profile and the hub of the account settings, reached from the account menu (not from the sidebar)
  screen('profile', '/profile', 'user', null, { inSidebar: false, requiresAuth: true }),
  screen('accountSettings', '/settings', 'gear', 'verwaltung', { requiresAuth: true }),
  // the only page of a workspace whose access has ended
  screen('accessEnded', '/access-ended', 'lock', null, { inSidebar: false }),
];

/** The menu entries one person gets: the screens for their kind of workspace, and the admin area for the admin. */
export function screensVisibleFor(
  list: readonly ScreenDefinition[],
  user:
    | {
        readonly workspaceType?: WorkspaceKind;
        readonly platformAdmin?: boolean;
        /** What the server granted; `undefined` means "not known", which hides nothing. */
        readonly permissions?: readonly string[];
        readonly subscription?: { readonly planTier?: number } | null;
      }
    | null
    | undefined,
): readonly ScreenDefinition[] {
  const tier = user?.subscription?.planTier;
  return list.filter((item) => {
    if (item.adminOnly) return Boolean(user?.platformAdmin);
    // An unknown plan hides nothing: the server decides, the menu only follows it.
    if (item.minTier !== undefined && tier !== undefined && tier < item.minTier) return false;
    // Same for permissions: only a list the server actually sent can hide a screen.
    if (
      item.requiredPermission !== undefined &&
      user?.permissions !== undefined &&
      !user.permissions.includes(item.requiredPermission)
    ) {
      return false;
    }
    if (!item.audience || !user?.workspaceType) return true;
    return item.audience.includes(user.workspaceType);
  });
}

export const sidebarScreens: readonly ScreenDefinition[] = screens.filter((item) => item.inSidebar);

export function screensBySection(section: SectionId): readonly ScreenDefinition[] {
  return sidebarScreens.filter((item) => item.section === section);
}

export function getScreen(id: ScreenId): ScreenDefinition {
  const found = screens.find((item) => item.id === id);
  if (!found) throw new Error(`Unknown screen id: ${id}`);
  return found;
}

/**
 * Resolves the active screen for a pathname. The longest matching static prefix
 * wins, so `/documents/123` resolves to the document registry (the viewer is its
 * detail route) while `/documents` stays exact.
 */
export function findScreenByPath(pathname: string): ScreenDefinition | undefined {
  const clean = pathname !== '/' && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname;
  const candidates = screens
    .filter((item) => !item.href.includes('['))
    .filter((item) =>
      item.href === '/' ? clean === '/' : clean === item.href || clean.startsWith(`${item.href}/`),
    )
    .sort((a, b) => b.href.length - a.href.length);
  return candidates[0];
}

/** Breadcrumb trail: sidebar section screen → detail screen. */
export function breadcrumbsForPath(
  pathname: string,
): readonly { readonly screen: ScreenDefinition; readonly isCurrent: boolean }[] {
  const clean = pathname !== '/' && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname;
  const current = findScreenByPath(clean);
  if (!current) return [];
  const isDetailRoute = clean !== current.href && current.href !== '/';
  if (!isDetailRoute) return [{ screen: current, isCurrent: true }];
  const detailIcon: IconName = 'file-text';
  const detail: ScreenDefinition = {
    ...current,
    id: 'documentViewer',
    href: clean,
    icon: detailIcon,
    inSidebar: false,
    titleKey: 'items.documentViewer',
  };
  return [
    { screen: current, isCurrent: false },
    { screen: detail, isCurrent: true },
  ];
}
