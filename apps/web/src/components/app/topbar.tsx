'use client';

import Link from 'next/link';
import { Icon } from '@/components/icons';
import { IconButton } from '@/components/ui/button';
import { Avatar } from '@/components/ui/data';
import { useAppearance } from '@/settings/appearance-provider';
import { localeNames, locales, type Locale } from '@/i18n/config';
import { useLocale, useSetLocale, useTranslations } from '@/i18n/provider';
import type { SessionUser } from '@/lib/api/types';
import { DropdownMenu, type MenuItemDefinition } from './dropdown-menu';
import { WorkspaceSwitcher } from './workspace-switcher';
import styles from './topbar.module.css';

export interface TopbarProps {
  readonly onOpenSearch: () => void;
  readonly onOpenMobileNav: () => void;
  readonly user: SessionUser | null;
  readonly unreadNotifications?: number;
  /** Documents waiting for a person's check; shown as a link so that nothing waits unnoticed. */
  readonly pendingReview?: number;
  readonly onOpenNotifications?: () => void;
  readonly onSignOut?: () => void;
}

/**
 * Top bar: search/command palette trigger, notifications, appearance, language
 * and the account menu. On mobile it collapses to a menu button plus the
 * essential controls (docs/task.md §5.2, §23.4 — mobile support).
 */
export function Topbar({
  onOpenSearch,
  onOpenMobileNav,
  user,
  unreadNotifications = 0,
  pendingReview = 0,
  onOpenNotifications,
  onSignOut,
}: TopbarProps) {
  const t = useTranslations('nav');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const setLocale = useSetLocale();
  const { settings, update, reset } = useAppearance();

  const themeItems: MenuItemDefinition[] = [
    {
      id: 'light',
      label: t('themeLight'),
      icon: 'sun',
      checked: settings.theme === 'light',
      onSelect: () => update({ theme: 'light' }),
    },
    {
      id: 'dark',
      label: t('themeDark'),
      icon: 'moon',
      checked: settings.theme === 'dark',
      onSelect: () => update({ theme: 'dark' }),
    },
    {
      id: 'system',
      label: t('themeSystem'),
      icon: 'monitor',
      checked: settings.theme === 'system',
      onSelect: () => update({ theme: 'system' }),
    },
    {
      id: 'reset',
      label: tCommon('actions.reset'),
      icon: 'refresh',
      onSelect: reset,
    },
  ];

  const languageItems: MenuItemDefinition[] = locales.map((item: Locale) => ({
    id: item,
    label: localeNames[item],
    icon: 'globe',
    checked: item === locale,
    onSelect: () => setLocale(item),
  }));

  const accountItems: MenuItemDefinition[] = [
    { id: 'profile', label: t('profile'), icon: 'user', disabled: !user },
    { id: 'settings', label: t('accountSettings'), icon: 'gear' },
    ...(user
      ? [
          {
            id: 'signout',
            label: t('signOut'),
            icon: 'logout' as const,
            danger: true,
            onSelect: onSignOut,
          },
        ]
      : []),
  ];

  return (
    <header className={styles.topbar}>
      <div className={styles.left}>
        <span className={styles.mobileOnly}>
          <IconButton label={t('openNavigation')} icon="menu" onClick={onOpenMobileNav} />
        </span>
        {user ? <WorkspaceSwitcher currentName={user.workspaceName} /> : null}
        <button type="button" className={styles.searchButton} onClick={onOpenSearch}>
          <Icon name="search" size={16} />
          <span className={styles.searchLabel}>{t('search')}</span>
          <kbd className={styles.kbd}>{t('searchShortcut')}</kbd>
        </button>
      </div>

      <div className={styles.right}>
        {pendingReview > 0 ? (
          <a href="/review" data-testid="pending-review" style={{ fontWeight: 600, textDecoration: 'underline' }}>
            {t('pendingReview', { count: pendingReview })}
          </a>
        ) : null}
        <DropdownMenu
          label={t('notifications')}
          align="end"
          items={[
            {
              id: 'all',
              label: t('notificationsOpen'),
              icon: 'bell',
              onSelect: () => onOpenNotifications?.(),
            },
          ]}
          header={<span>{t('notificationsUnread', { count: unreadNotifications })}</span>}
          trigger={
            <IconButton
              label={t('notifications')}
              icon={
                <span className={styles.badgeWrapper}>
                  <Icon name="bell" size={18} />
                  {unreadNotifications > 0 ? (
                    <span className={styles.badge} aria-hidden="true">
                      {unreadNotifications > 99 ? '99+' : unreadNotifications}
                    </span>
                  ) : null}
                </span>
              }
            />
          }
        />

        <DropdownMenu
          label={t('theme')}
          align="end"
          items={themeItems}
          trigger={
            <IconButton label={t('theme')} icon={settings.theme === 'dark' ? 'moon' : 'sun'} />
          }
        />

        <DropdownMenu
          label={t('languageChange')}
          align="end"
          items={languageItems}
          trigger={<IconButton label={t('languageChange')} icon="globe" />}
        />

        <DropdownMenu
          label={t('userMenu')}
          align="end"
          items={accountItems}
          footer={
            user ? (
              <Link href="/settings/security" className={styles.menuLink}>
                {t('accountSettings')}
              </Link>
            ) : (
              <Link href="/login" className={styles.menuLink}>
                {t('signIn')}
              </Link>
            )
          }
          trigger={
            <button
              type="button"
              className={styles.accountButton}
              aria-label={user ? t('userMenuLabel', { name: user.name }) : t('userMenu')}
            >
              <Avatar name={user?.name ?? '?'} size="sm" />
              <span className={styles.accountName}>{user?.name ?? t('signIn')}</span>
              <Icon name="chevron-down" size={14} />
            </button>
          }
        />
      </div>
    </header>
  );
}
