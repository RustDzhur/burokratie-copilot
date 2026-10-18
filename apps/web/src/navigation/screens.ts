import type { IconName } from '@/components/icons';

/**
 * Canonical registry of every screen required by docs/task.md §5.2.
 *
 * The sidebar, breadcrumbs, command palette, page headers and the automated
 * navigation test all read from this single registry — a screen cannot be
 * "forgotten" in one place while existing in another.
 */

export type SectionId =
  'workspace' | 'documents' | 'finance' | 'organisation' | 'automation' | 'governance';

export interface NavSection {
  readonly id: SectionId;
  readonly labelKey:
    | 'sections.workspace'
    | 'sections.documents'
    | 'sections.finance'
    | 'sections.organisation'
    | 'sections.automation'
    | 'sections.governance';
}

export const navSections: readonly NavSection[] = [
  { id: 'workspace', labelKey: 'sections.workspace' },
  { id: 'documents', labelKey: 'sections.documents' },
  { id: 'finance', labelKey: 'sections.finance' },
  { id: 'organisation', labelKey: 'sections.organisation' },
  { id: 'automation', labelKey: 'sections.automation' },
  { id: 'governance', labelKey: 'sections.governance' },
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
  | 'radar'
  | 'approvals'
  | 'future'
  | 'rules'
  | 'calendar'
  | 'contracts'
  | 'invoices' | 'generated-documents'
  | 'payments'
  | 'contacts'
  | 'mail'
  | 'templates'
  | 'assistant'
  | 'automations'
  | 'integrations'
  | 'scan'
  | 'parties'
  | 'family'
  | 'company'
  | 'employees'
  | 'reports'
  | 'archive'
  | 'security'
  | 'accessibilitySettings'
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
  screen('dashboard', '/dashboard', 'grid', 'workspace'),
  // §5.2.5 — inbox
  screen('inbox', '/inbox', 'inbox', 'workspace'),
  // §5.2.6 — universal document viewer (reached from registry/inbox)
  screen('documentViewer', '/documents/[id]', 'file-text', 'documents', { inSidebar: false }),
  // §5.2.7 — review centre
  screen('review', '/review', 'check-square', 'workspace'),
  // §5.2.8 — document registry
  screen('documents', '/documents', 'folder', 'documents'),
  // §5.2.9 — cases / Akten / Fälle
  screen('cases', '/cases', 'briefcase', 'documents'),
  // §5.2.10 — tasks and workflows
  screen('tasks', '/tasks', 'check-circle', 'workspace'),
  // Bürokratie-Radar (ТЗ v2.0): signals that several sources point to together
  screen('radar', '/radar', 'alert-triangle', 'workspace', { minTier: 1 }),
  // approval centre: what waits for a person's decision, with the material
  screen('approvals', '/approvals', 'shield', 'workspace', { minTier: 1 }),
  // the future: what is recorded to happen in the next 30, 60 or 90 days, with what is not known
  screen('future', '/future', 'calendar', 'workspace'),
  // §5.2.11 — calendar and deadlines
  screen('calendar', '/calendar', 'calendar', 'workspace'),
  // §5.2.12 — contracts
  screen('contracts', '/contracts', 'layout', 'finance', { minTier: 1 }),
  // §5.2.13 — invoices and expenses
  screen('invoices', '/invoices', 'receipt', 'finance', { audience: ['company'], minTier: 2 }),
  // §5.2.14 — payments and reconciliation
  screen('payments', '/payments', 'credit-card', 'finance', { minTier: 2, audience: ['company'] }),
  // §5.2.15 — contacts and organisations
  screen('contacts', '/contacts', 'users', 'organisation', { minTier: 0 }),
  // §5.2.16 — mail centre
  screen('mail', '/mail', 'mail', 'organisation', { minTier: 1 }),
  // §5.2.17 — template editor
  screen('templates', '/templates', 'pencil', 'documents', { minTier: 1 }),
  // §5.2.18 — AI assistant
  screen('assistant', '/assistant', 'sparkles', 'workspace', { minTier: 1 }),
  // §5.2.19 — automations
  screen('automations', '/automations', 'zap', 'automation', { minTier: 2 }),
  // §5.2.20 — integrations
  screen('integrations', '/integrations', 'plug', 'automation', { minTier: 1 }),
  // §5.2.21 — scanning and import
  screen('scan', '/scan', 'printer', 'automation', { minTier: 1 }),
  // whom the cabinet belongs to: the person, the household members, the company
  screen('parties', '/parties', 'users', 'organisation'),
  // §5.2.22 — family cockpit
  screen('family', '/family', 'shield-heart', 'organisation', { audience: ['family'], minTier: 1 }),
  // §5.2.23 — company workspace
  screen('company', '/company', 'building', 'organisation', { audience: ['company'], minTier: 2 }),
  // §5.2.24 — employees and roles
  screen('employees', '/employees', 'user-cog', 'organisation', { audience: ['company'], minTier: 3 }),
  // §5.2.25 — reports and analytics
  screen('reports', '/reports', 'bar-chart', 'governance', { minTier: 1 }),
  // §5.2.26 — archive and retention
  screen('archive', '/archive', 'archive', 'documents'),
  // §5.2.27 — security settings
  screen('security', '/security', 'shield', 'governance'),
  // §5.2.28 — accessibility settings
  screen('accessibilitySettings', '/accessibility', 'accessibility', 'governance'),
  // thresholds and switches of the Radar and of document recognition, with a version history
  screen('rules', '/rules', 'shield', 'governance'),
  // §5.2.29 — notification centre
  screen('notifications', '/notifications', 'bell', 'governance'),
  // §5.2.30 — activity history and audit
  screen('audit', '/audit', 'clock', 'governance', { minTier: 3 }),
  // plan, payment and invoices of the workspace
  screen('billing', '/billing', 'credit-card', 'governance'),
  // the platform administrator's area (customers, prices, payments, global settings)
  screen('admin', '/admin', 'shield', 'governance', { adminOnly: true }),
  // the person's own profile and the hub of the account settings, reached from the account menu (not from the sidebar)
  screen('profile', '/profile', 'user', null, { inSidebar: false, requiresAuth: true }),
  screen('accountSettings', '/settings', 'gear', null, { inSidebar: false, requiresAuth: true }),
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
