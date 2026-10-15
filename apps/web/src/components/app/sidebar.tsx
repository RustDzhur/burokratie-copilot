'use client';

import Link from 'next/link';
import { BrandMark } from '@/components/app/brand-mark';
import { usePathname } from 'next/navigation';
import { Icon } from '@/components/icons';
import { IconButton } from '@/components/ui/button';
import { useTranslations } from '@/i18n/provider';
import { useSession } from '@/lib/auth/session-context';
import { navSections, screensBySection, screensVisibleFor } from '@/navigation/screens';
import styles from './sidebar.module.css';

export interface SidebarProps {
  readonly collapsed: boolean;
  readonly onToggleCollapse: () => void;
  /** Called after a link is activated (used to close the mobile drawer). */
  readonly onNavigate?: () => void;
  readonly variant?: 'desktop' | 'mobile';
}

/**
 * Primary navigation. Sections and items come from the screen registry, so the
 * sidebar always mirrors the 30 screens required by docs/task.md §5.2.
 *
 * Keyboard: a single logical tab order (skip link → sidebar → topbar → main),
 * `aria-current="page"` on the active screen and visible focus everywhere.
 */
export function Sidebar({
  collapsed,
  onToggleCollapse,
  onNavigate,
  variant = 'desktop',
}: SidebarProps) {
  const t = useTranslations('nav');
  const tCommon = useTranslations('common');
  const pathname = usePathname();
  const session = useSession();
  const isCollapsed = collapsed && variant === 'desktop';

  return (
    <nav
      className={[styles.sidebar, isCollapsed ? styles.sidebarCollapsed : '']
        .filter(Boolean)
        .join(' ')}
      aria-label={t('primaryLabel')}
      data-variant={variant}
    >
      <div className={styles.brand}>
        <Link href="/dashboard" className={styles.brandLink} onClick={onNavigate}>
          <BrandMark size={34} />
          {!isCollapsed ? <span className={styles.brandName}>{tCommon('app.name')}</span> : null}
        </Link>
        {!isCollapsed && variant === 'desktop' ? (
          <IconButton
            label={t('toggleNavigation')}
            icon="chevron-left"
            size="sm"
            onClick={onToggleCollapse}
          />
        ) : null}
      </div>

      {!isCollapsed && session?.workspaceType ? (
        <p className={styles.cabinet} data-testid="cabinet-badge">
          {t('cabinet.badge', {
            cabinet: t(`cabinet.${session.workspaceType}` as 'cabinet.private'),
            plan: session.subscription?.planName ?? '—',
          })}
        </p>
      ) : null}

      <div className={styles.scrollArea}>
        {navSections.map((section) => {
          const items = screensVisibleFor(screensBySection(section.id), session);
          if (items.length === 0) return null;
          return (
            <div className={styles.section} key={section.id}>
              {!isCollapsed ? (
                <p className={styles.sectionLabel} id={`nav-section-${section.id}`}>
                  {t(section.labelKey)}
                </p>
              ) : (
                <span className={styles.sectionDivider} aria-hidden="true" />
              )}
              <ul
                className={styles.list}
                aria-labelledby={isCollapsed ? undefined : `nav-section-${section.id}`}
              >
                {items.map((item) => {
                  const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                  return (
                    <li key={item.id}>
                      <Link
                        href={item.href}
                        className={[styles.link, active ? styles.linkActive : '']
                          .filter(Boolean)
                          .join(' ')}
                        aria-current={active ? 'page' : undefined}
                        title={isCollapsed ? t(item.titleKey) : undefined}
                        onClick={onNavigate}
                      >
                        <span className={styles.linkIcon} aria-hidden="true">
                          <Icon name={item.icon} size={18} />
                        </span>
                        <span className={isCollapsed ? styles.visuallyHidden : styles.linkLabel}>
                          {t(item.titleKey)}
                        </span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </div>

      {variant === 'desktop' ? (
        <div className={styles.footer}>
          <button type="button" className={styles.collapseButton} onClick={onToggleCollapse}>
            <span className={styles.linkIcon} aria-hidden="true">
              <Icon name={collapsed ? 'chevron-right' : 'chevron-left'} size={18} />
            </span>
            <span className={isCollapsed ? styles.visuallyHidden : styles.linkLabel}>
              {t('toggleNavigation')}
            </span>
          </button>
        </div>
      ) : null}
    </nav>
  );
}
