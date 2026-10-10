'use client';

import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useRouter } from 'next/navigation';
import { createPortal } from 'react-dom';
import { Icon, type IconName } from '@/components/icons';
import { useTranslations } from '@/i18n/provider';
import styles from './command-palette.module.css';

export interface CommandItem {
  readonly id: string;
  readonly label: string;
  readonly group: string;
  readonly icon?: IconName;
  /** Navigates to this route when the command is confirmed. */
  readonly href?: string;
  /** Extra search terms (synonyms, IDs). */
  readonly keywords?: string;
  readonly shortcut?: string;
  readonly onSelect?: () => void;
}

export interface CommandPaletteProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly items: readonly CommandItem[];
}

function normalize(value: string): string {
  return value
    .toLocaleLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
}

/**
 * Keyboard-first command palette (Ctrl/Cmd + K).
 *
 * Implements the combobox + listbox pattern: `aria-activedescendant` follows the
 * highlighted option, Arrow/Home/End move, Enter runs the command and Escape
 * closes. Mouse users get the same list; both paths are announced identically.
 */
export function CommandPalette({ open, onClose, items }: CommandPaletteProps) {
  const t = useTranslations('nav');
  const router = useRouter();
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listId = useId();

  const results = useMemo(() => {
    const needle = normalize(query.trim());
    if (needle === '') return items;
    return items.filter((item) =>
      normalize(`${item.label} ${item.group} ${item.keywords ?? ''}`).includes(needle),
    );
  }, [items, query]);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActiveIndex(0);
      const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
      return () => window.clearTimeout(timer);
    }
    return undefined;
  }, [open]);

  useEffect(() => {
    setActiveIndex((current) => (current >= results.length ? 0 : current));
  }, [results.length]);

  if (!open) return null;

  const run = (item: CommandItem | undefined): void => {
    if (!item) return;
    onClose();
    if (item.href) router.push(item.href);
    item.onSelect?.();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveIndex((current) => (results.length === 0 ? 0 : (current + 1) % results.length));
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActiveIndex((current) =>
        results.length === 0 ? 0 : (current - 1 + results.length) % results.length,
      );
      return;
    }
    if (event.key === 'Home') {
      event.preventDefault();
      setActiveIndex(0);
      return;
    }
    if (event.key === 'End') {
      event.preventDefault();
      setActiveIndex(Math.max(0, results.length - 1));
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      run(results[activeIndex]);
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    }
  };

  let renderedIndex = -1;

  return createPortal(
    <div className={styles.portal} role="presentation">
      <div className={styles.backdrop} aria-hidden="true" onMouseDown={onClose} />
      <div className={styles.layer}>
        <div className={styles.panel} role="dialog" aria-modal="true" aria-label={t('searchLabel')}>
          <div className={styles.inputRow}>
            <Icon name="search" size={18} />
            <input
              ref={inputRef}
              type="text"
              role="combobox"
              className={styles.input}
              placeholder={t('search')}
              aria-expanded="true"
              aria-controls={listId}
              aria-activedescendant={
                results[activeIndex] ? `${listId}-${results[activeIndex].id}` : undefined
              }
              aria-autocomplete="list"
              aria-label={t('searchLabel')}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={onKeyDown}
            />
            <kbd className={styles.kbd}>Esc</kbd>
          </div>

          {results.length === 0 ? (
            <div className={styles.empty} role="status">
              <p className={styles.emptyTitle}>{t('commands.empty', { query })}</p>
              <p className={styles.emptyHint}>{t('commands.emptyHint')}</p>
            </div>
          ) : (
            <ul className={styles.list} id={listId} role="listbox" aria-label={t('commands.label')}>
              {results.map((item) => {
                renderedIndex += 1;
                const index = renderedIndex;
                const previous = results[index - 1];
                const showGroup = !previous || previous.group !== item.group;
                return (
                  <li key={item.id} role="presentation">
                    {showGroup ? (
                      <p className={styles.groupLabel} role="presentation">
                        {item.group}
                      </p>
                    ) : null}
                    <button
                      type="button"
                      id={`${listId}-${item.id}`}
                      role="option"
                      aria-selected={index === activeIndex}
                      className={[styles.option, index === activeIndex ? styles.optionActive : '']
                        .filter(Boolean)
                        .join(' ')}
                      onMouseEnter={() => setActiveIndex(index)}
                      onClick={() => run(item)}
                    >
                      <span className={styles.optionIcon} aria-hidden="true">
                        <Icon name={item.icon ?? 'arrow-right'} size={16} />
                      </span>
                      <span className={styles.optionLabel}>{item.label}</span>
                      {item.shortcut ? <kbd className={styles.kbd}>{item.shortcut}</kbd> : null}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}
