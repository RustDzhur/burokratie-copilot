'use client';

import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import styles from './tabs.module.css';

export interface TabItem {
  readonly id: string;
  readonly label: string;
  readonly content?: ReactNode;
  readonly badge?: ReactNode;
  readonly disabled?: boolean;
}

export interface TabsProps {
  /** Accessible name of the tab list, e.g. "Document sections". */
  readonly label: string;
  readonly tabs: readonly TabItem[];
  readonly value?: string;
  readonly defaultValue?: string;
  readonly onChange?: (id: string) => void;
  readonly className?: string;
}

/**
 * Tabs with the full WAI-ARIA pattern: roving tabindex, Arrow/Home/End keys and
 * `aria-controls`/`aria-labelledby` wiring between tab and panel.
 */
export function Tabs({ label, tabs, value, defaultValue, onChange, className }: TabsProps) {
  const [internal, setInternal] = useState<string>(defaultValue ?? tabs[0]?.id ?? '');
  const activeId = value ?? internal;
  const listRef = useRef<HTMLDivElement | null>(null);

  const select = (id: string): void => {
    if (value === undefined) setInternal(id);
    onChange?.(id);
  };

  const focusTab = (index: number): void => {
    const buttons = listRef.current?.querySelectorAll<HTMLButtonElement>(
      '[role="tab"]:not([disabled])',
    );
    if (!buttons || buttons.length === 0) return;
    const list = Array.from(buttons);
    const bounded = ((index % list.length) + list.length) % list.length;
    list[bounded]?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>, index: number): void => {
    switch (event.key) {
      case 'ArrowRight':
        event.preventDefault();
        focusTab(index + 1);
        break;
      case 'ArrowLeft':
        event.preventDefault();
        focusTab(index - 1);
        break;
      case 'Home':
        event.preventDefault();
        focusTab(0);
        break;
      case 'End':
        event.preventDefault();
        focusTab(-1);
        break;
      default:
        break;
    }
  };

  return (
    <div className={[styles.tabs, className].filter(Boolean).join(' ')}>
      <div className={styles.tabList} role="tablist" aria-label={label} ref={listRef}>
        {tabs.map((tab, index) => {
          const selected = tab.id === activeId;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              id={`tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={`panel-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              disabled={tab.disabled}
              className={[styles.tab, selected ? styles.tabActive : ''].filter(Boolean).join(' ')}
              onClick={() => select(tab.id)}
              onKeyDown={(event) => onKeyDown(event, index)}
            >
              <span>{tab.label}</span>
              {tab.badge ? <span className={styles.tabBadge}>{tab.badge}</span> : null}
            </button>
          );
        })}
      </div>
      {tabs.map((tab) => (
        <div
          key={tab.id}
          role="tabpanel"
          id={`panel-${tab.id}`}
          aria-labelledby={`tab-${tab.id}`}
          hidden={tab.id !== activeId}
          tabIndex={0}
          className={styles.panel}
        >
          {tab.id === activeId ? tab.content : null}
        </div>
      ))}
    </div>
  );
}
