'use client';
import { CookieSettingsButton } from '@/components/app/cookie-consent';

import { LEGAL_LINKS } from '@/legal/links';
import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Drawer } from '@/components/ui/overlay';
import { CommandPalette, type CommandItem } from '@/components/ui/command-palette';
import { useTranslations } from '@/i18n/provider';
import { api, onPaymentRequired, onUnauthorized } from '@/lib/api';
import { SessionProvider } from '@/lib/auth/session-context';
import { useApiResource } from '@/lib/api/hooks';
import type { SessionUser } from '@/lib/api/types';
import { screens, screensVisibleFor } from '@/navigation/screens';
import { QuickCapture } from './quick-capture';
import { SubscriptionBanner } from './subscription-banner';
import { Sidebar } from './sidebar';
import { DemoBanner, isDemoUser } from './demo-banner';
import { Topbar } from './topbar';
import styles from './app-shell.module.css';

export interface AppShellProps {
  readonly children: ReactNode;
}

/**
 * Application shell: skip link, sidebar, topbar, main landmark and the command
 * palette. Logical tab order is skip link → sidebar → topbar → content, and the
 * mobile navigation opens in an accessible drawer.
 *
 * The session is loaded through the API client. When the backend is unreachable
 * the shell still renders and degrades to a "sign in" account state instead of
 * showing fabricated user data.
 */
export function AppShell({ children }: AppShellProps) {
  const t = useTranslations('nav');
  const tCommon = useTranslations('common');
  const pathname = usePathname();
  const router = useRouter();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  const { state: sessionState, reload: reloadSession } = useApiResource<SessionUser>(
    (signal) => api.auth.session(signal),
    [],
    { keepPreviousData: true },
  );
  const user = sessionState.status === 'ready' ? sessionState.data : null;

  const { state: unreadState } = useApiResource<number>(
    (signal) => api.notifications.unreadCount(signal),
    [pathname],
    { keepPreviousData: true },
  );
  const unread = unreadState.status === 'ready' ? unreadState.data : 0;

  // Documents that wait for a person's check: always visible, because a document that waits unnoticed looks like a lost one.
  const { state: reviewState } = useApiResource<number>(
    async (signal) => (await api.review.queue({ size: 1 }, signal)).totalItems,
    [pathname],
    { keepPreviousData: true },
  );
  const pendingReview = reviewState.status === 'ready' ? reviewState.data : 0;

  // A page of the signed-in area without a valid session is not a page: send the person to sign in, and
  // bring them back here afterwards. The same happens when the session runs out while the page is open.
  useEffect(() => {
    const toSignIn = (): void => {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    };
    return onUnauthorized(toSignIn);
  }, [pathname, router]);

  // The workspace's access ended: the only page left is the one where the plan is renewed or the data dealt with.
  const blocked = user?.subscription?.blocked === true;
  const onBillingPage = pathname.startsWith('/billing') || pathname.startsWith('/access-ended');
  useEffect(() => {
    const toAccessEnded = (): void => {
      if (!onBillingPage) router.replace('/access-ended');
    };
    if (blocked) toAccessEnded();
    return onPaymentRequired(toAccessEnded);
  }, [blocked, onBillingPage, router]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen(true);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    setMobileNavOpen(false);
  }, [pathname]);

  const commandItems = useMemo<CommandItem[]>(() => {
    const navigation: CommandItem[] = screensVisibleFor(
      screens.filter((screen) => screen.inSidebar),
      user,
    ).map((screen) => ({
        id: `nav-${screen.id}`,
        label: t(screen.titleKey),
        group: t('commands.navigation'),
        icon: screen.icon,
        href: screen.href,
      }));
    const actions: CommandItem[] = [
      {
        id: 'action-upload',
        label: t('commands.newDocument'),
        group: t('commands.label'),
        icon: 'upload',
        href: '/inbox',
      },
      {
        id: 'action-accessibility',
        label: t('commands.openSettings'),
        group: t('commands.label'),
        icon: 'accessibility',
        href: '/accessibility',
      },
    ];
    return [...navigation, ...actions];
  }, [t, user]);

  const handleSignOut = useCallback(async () => {
    try {
      await api.auth.logout();
    } finally {
      router.push(isDemoUser(user) ? '/' : '/login');
    }
  }, [router, user]);

  return (
    <SessionProvider user={user} reload={reloadSession}>
    <div className={styles.shell}>
      <a className={styles.skipLink} href="#main-content">
        {t('skipToContent')}
      </a>

      <div className={styles.sidebarSlot}>
        <Sidebar collapsed={collapsed} onToggleCollapse={() => setCollapsed((value) => !value)} />
      </div>

      <div className={styles.main}>
        <DemoBanner user={user} />
        <Topbar
          onOpenSearch={() => setPaletteOpen(true)}
          onOpenMobileNav={() => setMobileNavOpen(true)}
          user={user}
          unreadNotifications={unread}
          pendingReview={pendingReview}
          blocked={blocked}
          onOpenNotifications={() => router.push('/notifications')}
          onSignOut={() => {
            void handleSignOut();
          }}
        />
        <SubscriptionBanner subscription={user?.subscription ?? null} hidden={onBillingPage || isDemoUser(user)} />
        <main id="main-content" tabIndex={-1} className={styles.content}>
          {blocked && !onBillingPage ? null : children}
        </main>
        <footer className={styles.footer}>
          <p>
            {tCommon('app.name')} · {tCommon('app.tagline')}
          </p>
          <nav aria-label="Rechtliche Hinweise" lang="de" style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
            {LEGAL_LINKS.map((link) => (
              <Link key={link.href} href={link.href}>
                {link.label}
              </Link>
            ))}
          <CookieSettingsButton />
          </nav>
        </footer>
      </div>

      <Drawer
        open={mobileNavOpen}
        onClose={() => setMobileNavOpen(false)}
        title={t('primaryLabel')}
        side="left"
        size="sm"
      >
        <Sidebar
          collapsed={false}
          variant="mobile"
          onToggleCollapse={() => setMobileNavOpen(false)}
          onNavigate={() => setMobileNavOpen(false)}
        />
      </Drawer>

      {blocked ? null : <QuickCapture />}

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        items={commandItems}
      />
    </div>
    </SessionProvider>
  );
}
