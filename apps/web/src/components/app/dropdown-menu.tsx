'use client';

import {
  cloneElement,
  useEffect,
  useId,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from 'react';
import { Icon, type IconName } from '@/components/icons';
import styles from './dropdown-menu.module.css';

export interface MenuItemDefinition {
  readonly id: string;
  readonly label: string;
  readonly icon?: IconName;
  readonly shortcut?: string;
  readonly checked?: boolean;
  readonly danger?: boolean;
  readonly disabled?: boolean;
  readonly onSelect?: () => void;
}

export interface DropdownMenuProps {
  /** Accessible name of the menu. */
  readonly label: string;
  readonly trigger: ReactElement<{
    onClick?: () => void;
    'aria-expanded'?: boolean;
    'aria-haspopup'?: 'menu';
    'aria-controls'?: string;
  }>;
  readonly items: readonly MenuItemDefinition[];
  readonly align?: 'start' | 'end';
  readonly header?: ReactNode;
  readonly footer?: ReactNode;
  readonly className?: string;
}

/**
 * Accessible dropdown menu.
 *
 * Menu items use `role="menuitem"` with roving focus; Arrow keys, Home/End move,
 * Escape closes and returns focus to the trigger, and clicking outside closes.
 */
export function DropdownMenu({
  label,
  trigger,
  items,
  align = 'end',
  header,
  footer,
  className,
}: DropdownMenuProps) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: MouseEvent): void => {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: globalThis.KeyboardEvent): void => {
      if (event.key === 'Escape') {
        setOpen(false);
        wrapperRef.current?.querySelector<HTMLElement>('button')?.focus();
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const focusItem = (index: number): void => {
    const nodes = menuRef.current?.querySelectorAll<HTMLButtonElement>(
      '[role="menuitem"]:not([disabled])',
    );
    if (!nodes || nodes.length === 0) return;
    const list = Array.from(nodes);
    const bounded = ((index % list.length) + list.length) % list.length;
    list[bounded]?.focus();
  };

  const onMenuKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    const nodes = menuRef.current?.querySelectorAll<HTMLButtonElement>(
      '[role="menuitem"]:not([disabled])',
    );
    const list = nodes ? Array.from(nodes) : [];
    const currentIndex = list.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      focusItem(currentIndex + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      focusItem(currentIndex - 1);
    } else if (event.key === 'Home') {
      event.preventDefault();
      focusItem(0);
    } else if (event.key === 'End') {
      event.preventDefault();
      focusItem(list.length - 1);
    }
  };

  const enhancedTrigger = cloneElement(trigger, {
    onClick: () => setOpen((current) => !current),
    'aria-expanded': open,
    'aria-haspopup': 'menu' as const,
    'aria-controls': open ? menuId : undefined,
  });

  return (
    <div className={[styles.wrapper, className].filter(Boolean).join(' ')} ref={wrapperRef}>
      {enhancedTrigger}
      {open ? (
        <div
          className={[styles.menu, align === 'end' ? styles.menuEnd : styles.menuStart].join(' ')}
          role="menu"
          id={menuId}
          aria-label={label}
          tabIndex={-1}
          ref={menuRef}
          onKeyDown={onMenuKeyDown}
        >
          {header ? <div className={styles.menuHeader}>{header}</div> : null}
          <ul className={styles.menuList} role="none">
            {items.map((item) => (
              <li key={item.id} role="none">
                <button
                  type="button"
                  role="menuitem"
                  className={[styles.menuItem, item.danger ? styles.menuItemDanger : '']
                    .filter(Boolean)
                    .join(' ')}
                  disabled={item.disabled}
                  onClick={() => {
                    setOpen(false);
                    item.onSelect?.();
                  }}
                >
                  <span className={styles.menuItemIcon} aria-hidden="true">
                    {item.checked !== undefined ? (
                      item.checked ? (
                        <Icon name="check" size={15} />
                      ) : (
                        <span className={styles.checkPlaceholder} />
                      )
                    ) : item.icon ? (
                      <Icon name={item.icon} size={16} />
                    ) : (
                      <span className={styles.checkPlaceholder} />
                    )}
                  </span>
                  <span className={styles.menuItemLabel}>{item.label}</span>
                  {item.shortcut ? <kbd className={styles.shortcut}>{item.shortcut}</kbd> : null}
                </button>
              </li>
            ))}
          </ul>
          {footer ? <div className={styles.menuFooter}>{footer}</div> : null}
        </div>
      ) : null}
    </div>
  );
}
