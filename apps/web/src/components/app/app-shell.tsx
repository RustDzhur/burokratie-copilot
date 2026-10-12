'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Drawer } from '@/components/ui/overlay';
import { CommandPalette, type CommandItem } from '@/components/ui/command-palette';
import { useTranslations } from '@/i18n/provider';
import { api, onUnauthorized } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { SessionUser } from '@/lib/api/types';
import { screens } from '@/navigation/screens';
import { Sidebar } from './sidebar';
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

  const { state: sessionState } = useApiResource<SessionUser>(
    (signal) => api.auth.session(signal),
    [],
  );
  const user = sessionState.status === 'ready' ? sessionState.data : null;

  const { state: unreadState } = useApiResource<number>(
    (signal) => api.notifications.unreadCount(signal),
    [pathname],
    { keepPreviousData: true },
  );
  const unread = unreadState.status === 'ready' ? unreadState.data : 0;

  // A page of the signed-in area without a valid session is not a page: send the person to sign in, and
  // bring them back here afterwards. The same happens when the session runs out while the page is open.
  useEffect(() => {
    const toSignIn = (): void => {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    };
    return onUnauthorized(toSignIn);
  }, [pathname, router]);

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
    const navigation: CommandItem[] = screens
      .filter((screen) => screen.inSidebar)
      .map((screen) => ({
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
  }, [t]);

  const handleSignOut = useCallback(async () => {
    try {
      await api.auth.logout();
    } finally {
      router.push('/login');
    }
  }, [router]);

  return (
    <div className={styles.shell}>
      <a className={styles.skipLink} href="#main-content">
        {t('skipToContent')}
      </a>

      <div className={styles.sidebarSlot}>
        <Sidebar collapsed={collapsed} onToggleCollapse={() => setCollapsed((value) => !value)} />
      </div>

      <div className={styles.main}>
        <Topbar
          onOpenSearch={() => setPaletteOpen(true)}
          onOpenMobileNav={() => setMobileNavOpen(true)}
          user={user}
          unreadNotifications={unread}
          onOpenNotifications={() => router.push('/notifications')}
          onSignOut={() => {
            void handleSignOut();
          }}
        />
        <main id="main-content" tabIndex={-1} className={styles.content}>
          {children}
        </main>
        <footer className={styles.footer}>
          <p>
            {tCommon('app.name')} · {tCommon('app.tagline')}
          </p>
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

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        items={commandItems}
      />
    </div>
  );
}
