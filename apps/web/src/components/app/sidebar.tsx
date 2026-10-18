'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useCallback, useEffect, useRef, useState, type DragEvent, type ReactNode } from 'react';
import { BrandMark } from '@/components/app/brand-mark';
import { Icon } from '@/components/icons';
import { Button, IconButton } from '@/components/ui/button';
import { LiveRegion } from '@/components/ui/feedback';
import { ConfirmDialog } from '@/components/ui/overlay';
import { useTranslations } from '@/i18n/provider';
import { useSession } from '@/lib/auth/session-context';
import { navSections, type ScreenDefinition } from '@/navigation/screens';
import { usePersonalMenu } from './use-personal-menu';
import styles from './sidebar.module.css';

export interface SidebarProps {
  readonly collapsed: boolean;
  readonly onToggleCollapse: () => void;
  /** Called after a link is activated (used to close the mobile drawer). */
  readonly onNavigate?: () => void;
  readonly variant?: 'desktop' | 'mobile';
}

/** Where the browser remembers whether "Weitere" was left open. Purely a UI preference, per browser. */
const WEITERE_STORAGE_KEY = 'bc.sidebar.weitere';
const WEITERE_REGION_ID = 'nav-weitere';

interface DropTarget {
  readonly id: string;
  readonly position: 'before' | 'after';
}

/**
 * Primary navigation. Sections and items come from the screen registry, so the
 * sidebar always mirrors the 30 screens required by docs/task.md §5.2.
 *
 * <p>The menu is personal: the main list follows the order the person stored, the screens they do not
 * need wait in the collapsed "Weitere" block at the bottom, and the edit mode reorders and hides them.
 * An account with nothing stored sees the standard menu by sections — exactly the menu that existed
 * before personalisation, so a fresh or forgotten preference cannot change the familiar view.</p>
 *
 * <p>Keyboard: a single logical tab order (skip link → sidebar → topbar → main), `aria-current="page"`
 * on the active screen and visible focus everywhere. Reordering never depends on dragging: every item
 * has up/down buttons in edit mode, and every change is announced through a live region.</p>
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
  const { layout, isSaving, hide, show, move, drop, reset } = usePersonalMenu();

  const [editing, setEditing] = useState(false);
  const [weitereOpen, setWeitereOpen] = useState(false);
  const [weitereReady, setWeitereReady] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const [dropTarget, setDropTarget] = useState<DropTarget | null>(null);
  const draggedRef = useRef<string | null>(null);
  /** "Weitere" was opened only because edit mode needs it; leave it as it was on the way out. */
  const autoOpenedRef = useRef(false);

  // "Weitere" starts collapsed. The stored state is read after mount so the server and the first client
  // render agree (no hydration mismatch), and a browser without storage simply keeps the default.
  useEffect(() => {
    try {
      const stored = window.localStorage.getItem(WEITERE_STORAGE_KEY);
      if (stored === 'open') setWeitereOpen(true);
    } catch {
      // Storage may be unavailable (private mode); the default "collapsed" still applies.
    }
    setWeitereReady(true);
  }, []);

  useEffect(() => {
    if (!weitereReady) return;
    try {
      window.localStorage.setItem(WEITERE_STORAGE_KEY, weitereOpen ? 'open' : 'closed');
    } catch {
      // Remembering is a convenience; the menu works without it.
    }
  }, [weitereOpen, weitereReady]);

  const activeScreen = useCallback(
    (href: string): boolean => pathname === href || pathname.startsWith(`${href}/`),
    [pathname],
  );

  const labelOf = useCallback((item: ScreenDefinition): string => t(item.titleKey), [t]);

  const startEditing = (): void => {
    setEditing(true);
    if (!weitereOpen) {
      autoOpenedRef.current = true;
      setWeitereOpen(true);
    }
  };

  const stopEditing = (): void => {
    setEditing(false);
    if (autoOpenedRef.current) {
      autoOpenedRef.current = false;
      setWeitereOpen(false);
    }
  };

  const toggleEditing = (): void => {
    if (editing) stopEditing();
    else startEditing();
  };

  const toggleWeitere = (): void => {
    // While editing, "Weitere" is where the restore buttons live, so it stays open.
    if (editing) return;
    setWeitereOpen((open) => !open);
  };

  const handleDragStart = (event: DragEvent<HTMLLIElement>, id: string): void => {
    draggedRef.current = id;
    const transfer = event.dataTransfer as DataTransfer | undefined;
    if (transfer) {
      transfer.effectAllowed = 'move';
      // Some browsers refuse to start a drag without any data set.
      transfer.setData('text/plain', id);
    }
  };

  const handleDragOver = (event: DragEvent<HTMLLIElement>, id: string): void => {
    const dragged = draggedRef.current;
    if (!dragged || dragged === id) return;
    event.preventDefault();
    const transfer = event.dataTransfer as DataTransfer | undefined;
    if (transfer) transfer.dropEffect = 'move';
    const rect = event.currentTarget.getBoundingClientRect();
    const position: 'before' | 'after' =
      event.clientY < rect.top + rect.height / 2 ? 'before' : 'after';
    setDropTarget((current) =>
      current?.id === id && current.position === position ? current : { id, position },
    );
  };

  const handleDragEnd = (): void => {
    draggedRef.current = null;
    setDropTarget(null);
  };

  const handleDrop = (event: DragEvent<HTMLLIElement>, id: string): void => {
    event.preventDefault();
    const transfer = event.dataTransfer as DataTransfer | undefined;
    const dragged = draggedRef.current ?? transfer?.getData('text/plain') ?? '';
    const position = dropTarget?.id === id ? dropTarget.position : 'before';
    const moved = layout.main.find((item) => item.id === dragged);
    if (dragged && dragged !== id && moved) {
      drop(dragged, id, position);
      setAnnouncement(t('customise.moved', { name: labelOf(moved) }));
    }
    draggedRef.current = null;
    setDropTarget(null);
  };

  const handleMove = (item: ScreenDefinition, delta: number): void => {
    move(item.id, delta);
    setAnnouncement(
      t(delta < 0 ? 'customise.movedUp' : 'customise.movedDown', { name: labelOf(item) }),
    );
  };

  const handleHide = (item: ScreenDefinition): void => {
    hide(item.id);
    setAnnouncement(t('customise.hiddenAnnounce', { name: labelOf(item) }));
  };

  const handleShow = (item: ScreenDefinition): void => {
    show(item.id);
    setAnnouncement(t('customise.shownAnnounce', { name: labelOf(item) }));
  };

  const confirmReset = (): void => {
    setResetOpen(false);
    void reset();
  };

  const navLink = (item: ScreenDefinition): ReactNode => {
    const active = activeScreen(item.href);
    const label = labelOf(item);
    return (
      <Link
        key={item.id}
        href={item.href}
        className={[styles.link, active ? styles.linkActive : ''].filter(Boolean).join(' ')}
        aria-current={active ? 'page' : undefined}
        title={isCollapsed ? label : undefined}
        onClick={onNavigate}
      >
        <span className={styles.linkIcon} aria-hidden="true">
          <Icon name={item.icon} size={18} />
        </span>
        <span className={isCollapsed ? styles.visuallyHidden : styles.linkLabel}>{label}</span>
      </Link>
    );
  };

  const editableRow = (item: ScreenDefinition, index: number): ReactNode => {
    const label = labelOf(item);
    const dropPosition = dropTarget?.id === item.id ? dropTarget.position : null;
    return (
      <li
        key={item.id}
        className={[
          styles.item,
          editing ? styles.itemEditing : '',
          dropPosition === 'before' ? styles.dropBefore : '',
          dropPosition === 'after' ? styles.dropAfter : '',
        ]
          .filter(Boolean)
          .join(' ')}
        draggable={editing}
        onDragStart={editing ? (event) => handleDragStart(event, item.id) : undefined}
        onDragOver={editing ? (event) => handleDragOver(event, item.id) : undefined}
        onDragEnd={editing ? handleDragEnd : undefined}
        onDrop={editing ? (event) => handleDrop(event, item.id) : undefined}
      >
        {editing ? (
          <span className={styles.dragHandle} aria-hidden="true" title={t('customise.dragHandle')}>
            <Icon name="menu" size={16} />
          </span>
        ) : null}
        {navLink(item)}
        {editing ? (
          <span className={styles.itemActions}>
            <IconButton
              label={t('customise.moveUp', { name: label })}
              icon="chevron-up"
              size="sm"
              disabled={index <= 0}
              onClick={() => handleMove(item, -1)}
            />
            <IconButton
              label={t('customise.moveDown', { name: label })}
              icon="chevron-down"
              size="sm"
              disabled={index >= layout.main.length - 1}
              onClick={() => handleMove(item, 1)}
            />
            <IconButton
              label={t('customise.hide', { name: label })}
              icon="x"
              size="sm"
              onClick={() => handleHide(item)}
            />
          </span>
        ) : null}
      </li>
    );
  };

  const mainList = layout.personalised ? (
    <ul className={styles.list}>
      {layout.main.map((item, index) => editableRow(item, index))}
    </ul>
  ) : (
    navSections.map((section) => {
      const items = layout.main.filter((item) => item.section === section.id);
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
            {items.map((item) => editableRow(item, layout.main.indexOf(item)))}
          </ul>
        </div>
      );
    })
  );

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
        {!isCollapsed ? (
          <span className={styles.brandActions}>
            <IconButton
              label={t(editing ? 'customise.close' : 'customise.open')}
              icon="pencil"
              size="sm"
              pressed={editing}
              onClick={toggleEditing}
            />
            {variant === 'desktop' ? (
              <IconButton
                label={t('toggleNavigation')}
                icon="chevron-left"
                size="sm"
                onClick={onToggleCollapse}
              />
            ) : null}
          </span>
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

      {!isCollapsed && editing ? (
        <div className={styles.editPanel} role="group" aria-label={t('customise.editGroup')}>
          <p className={styles.editHint}>{t('customise.hint')}</p>
          <div className={styles.editActions}>
            <Button variant="ghost" size="sm" iconLeft="refresh" onClick={() => setResetOpen(true)}>
              {t('customise.reset')}
            </Button>
            <Button variant="primary" size="sm" iconLeft="check" onClick={stopEditing}>
              {t('customise.done')}
            </Button>
          </div>
          <p className={styles.editStatus} role="status">
            {isSaving ? t('customise.saving') : null}
          </p>
        </div>
      ) : null}

      <div className={styles.scrollArea}>
        <div data-testid="nav-main">{mainList}</div>

        {!isCollapsed && layout.hiddenIds.length > 0 ? (
          <div className={styles.additional}>
            <button
              type="button"
              className={styles.additionalToggle}
              aria-expanded={weitereOpen}
              aria-controls={WEITERE_REGION_ID}
              onClick={toggleWeitere}
            >
              <span className={styles.linkIcon} aria-hidden="true">
                <Icon name={weitereOpen ? 'chevron-up' : 'chevron-down'} size={16} />
              </span>
              <span className={styles.linkLabel}>{t('customise.weitere')}</span>
              <span className={styles.additionalCount}>{layout.hiddenIds.length}</span>
            </button>
            {weitereOpen ? (
              <div id={WEITERE_REGION_ID} className={styles.additionalBody}>
                {layout.additional.map(({ section, items }) => (
                  <div className={styles.section} key={section.id}>
                    <p className={styles.additionalSectionLabel} id={`nav-weitere-${section.id}`}>
                      {t(section.labelKey)}
                    </p>
                    <ul className={styles.list} aria-labelledby={`nav-weitere-${section.id}`}>
                      {items.map((item) => (
                        <li key={item.id} className={styles.item}>
                          {navLink(item)}
                          {editing ? (
                            <span className={styles.itemActions}>
                              <IconButton
                                label={t('customise.show', { name: labelOf(item) })}
                                icon="plus"
                                size="sm"
                                onClick={() => handleShow(item)}
                              />
                            </span>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}
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

      <LiveRegion message={announcement} />

      <ConfirmDialog
        open={resetOpen}
        title={t('customise.resetTitle')}
        description={t('customise.resetDescription')}
        confirmLabel={t('customise.resetConfirm')}
        onConfirm={confirmReset}
        onCancel={() => setResetOpen(false)}
      />
    </nav>
  );
}
