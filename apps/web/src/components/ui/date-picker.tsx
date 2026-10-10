'use client';

import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { Icon } from '@/components/icons';
import {
  addDays,
  addMonths,
  buildMonthMatrix,
  formatDate,
  fromIsoDate,
  isSameDay,
  isToday,
  parseDisplayDate,
  startOfMonth,
  toIsoDate,
} from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { IconButton } from './button';
import styles from './date-picker.module.css';

export interface DatePickerProps {
  /** ISO date (`YYYY-MM-DD`) or `null`; never a localized string. */
  readonly value: string | null;
  readonly onChange: (value: string | null) => void;
  readonly label?: ReactNode;
  readonly hint?: ReactNode;
  readonly error?: string;
  readonly required?: boolean;
  readonly disabled?: boolean;
  readonly min?: string;
  readonly max?: string;
  readonly id?: string;
  readonly className?: string;
  readonly placeholder?: string;
}

const WEEKDAY_KEYS = ['mo', 'tu', 'we', 'th', 'fr', 'sa', 'su'] as const;

/**
 * Lightweight date picker: a typed `dd.MM.yyyy` field plus a month grid popover.
 *
 * Keyboard contract: the input accepts typed German dates, the calendar is a
 * `role="grid"` with roving focus (arrows, Home/End, PageUp/PageDown), Enter
 * selects, Escape closes and returns focus to the input. Values stay ISO.
 */
export function DatePicker({
  value,
  onChange,
  label,
  hint,
  error,
  required = false,
  disabled = false,
  min,
  max,
  id,
  className,
  placeholder,
}: DatePickerProps) {
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const generatedId = useId();
  const baseId = id ?? generatedId;
  const hintId = hint ? `${baseId}-hint` : undefined;
  const errorId = error ? `${baseId}-error` : undefined;

  const selected = fromIsoDate(value);
  const [open, setOpen] = useState(false);
  const [text, setText] = useState<string>(selected ? formatDate(selected, locale, 'short') : '');
  const [viewMonth, setViewMonth] = useState<Date>(startOfMonth(selected ?? new Date()));
  const [focusDate, setFocusDate] = useState<Date>(selected ?? new Date());
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const next = fromIsoDate(value);
    setText(next ? formatDate(next, locale, 'short') : '');
    if (next) {
      setViewMonth(startOfMonth(next));
      setFocusDate(next);
    }
  }, [value, locale]);

  useEffect(() => {
    if (!open) return undefined;
    const onDocumentPointerDown = (event: MouseEvent): void => {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocumentPointerDown);
    return () => document.removeEventListener('mousedown', onDocumentPointerDown);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const iso = toIsoDate(focusDate);
    const node = wrapperRef.current?.querySelector<HTMLButtonElement>(`[data-date="${iso}"]`);
    node?.focus();
  }, [open, focusDate]);

  const weeks = useMemo(() => buildMonthMatrix(viewMonth), [viewMonth]);
  const formattedMonth = new Intl.DateTimeFormat(
    locale === 'de' ? 'de-DE' : locale === 'ru' ? 'ru-RU' : 'en-GB',
    {
      month: 'long',
      year: 'numeric',
    },
  ).format(viewMonth);

  const disabledByRange = (date: Date): boolean => {
    const iso = toIsoDate(date);
    if (min && iso < min) return true;
    if (max && iso > max) return true;
    return false;
  };

  const commit = (date: Date): void => {
    onChange(toIsoDate(date));
    setText(formatDate(date, locale, 'short'));
    setOpen(false);
    inputRef.current?.focus();
  };

  const onGridKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const moves: Record<string, number> = {
      ArrowLeft: -1,
      ArrowRight: 1,
      ArrowUp: -7,
      ArrowDown: 7,
    };
    const delta = moves[event.key];
    if (delta !== undefined) {
      event.preventDefault();
      const next = addDays(focusDate, delta);
      setFocusDate(next);
      setViewMonth(startOfMonth(next));
      return;
    }
    if (event.key === 'PageUp') {
      event.preventDefault();
      const next = addMonths(focusDate, -1);
      setFocusDate(next);
      setViewMonth(startOfMonth(next));
      return;
    }
    if (event.key === 'PageDown') {
      event.preventDefault();
      const next = addMonths(focusDate, 1);
      setFocusDate(next);
      setViewMonth(startOfMonth(next));
      return;
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault();
      const offset = (focusDate.getDay() + 6) % 7;
      const next = addDays(focusDate, event.key === 'Home' ? -offset : 6 - offset);
      setFocusDate(next);
      setViewMonth(startOfMonth(next));
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      setOpen(false);
      inputRef.current?.focus();
    }
  };

  return (
    <div className={[styles.field, className].filter(Boolean).join(' ')} ref={wrapperRef}>
      {label ? (
        <label className={styles.label} htmlFor={baseId} data-required={required || undefined}>
          {label}
        </label>
      ) : null}
      <div
        className={[styles.control, error ? styles.controlInvalid : ''].filter(Boolean).join(' ')}
      >
        <input
          ref={inputRef}
          id={baseId}
          type="text"
          inputMode="numeric"
          className={styles.input}
          value={text}
          placeholder={placeholder ?? 'TT.MM.JJJJ'}
          disabled={disabled}
          required={required}
          aria-describedby={[hintId, errorId].filter(Boolean).join(' ') || undefined}
          aria-invalid={error ? true : undefined}
          onChange={(event) => setText(event.target.value)}
          onBlur={() => {
            const parsed = parseDisplayDate(text);
            if (parsed) {
              onChange(toIsoDate(parsed));
              setText(formatDate(parsed, locale, 'short'));
            } else if (text.trim() === '') {
              onChange(null);
            } else {
              setText(selected ? formatDate(selected, locale, 'short') : '');
            }
          }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown' && !open) {
              event.preventDefault();
              setOpen(true);
            }
          }}
        />
        <IconButton
          label={tCommon('actions.today')}
          icon="calendar"
          size="sm"
          disabled={disabled}
          aria-expanded={open}
          aria-haspopup="dialog"
          onClick={() => setOpen((current) => !current)}
        />
      </div>
      {hint ? (
        <p className={styles.hint} id={hintId}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className={styles.error} id={errorId} role="alert">
          <Icon name="alert-circle" size={14} />
          <span>{error}</span>
        </p>
      ) : null}

      {open ? (
        <div className={styles.popover} role="dialog" aria-label={formattedMonth}>
          <div className={styles.popoverHeader}>
            <IconButton
              label={tCommon('actions.previous')}
              icon="chevron-left"
              size="sm"
              onClick={() => setViewMonth(addMonths(viewMonth, -1))}
            />
            <span className={styles.monthLabel} aria-live="polite">
              {formattedMonth}
            </span>
            <IconButton
              label={tCommon('actions.next')}
              icon="chevron-right"
              size="sm"
              onClick={() => setViewMonth(addMonths(viewMonth, 1))}
            />
          </div>
          <div role="grid" className={styles.grid} tabIndex={-1} onKeyDown={onGridKeyDown}>
            <div role="row" className={styles.gridRow}>
              {WEEKDAY_KEYS.map((key) => (
                <span key={key} role="columnheader" className={styles.weekday} aria-label={key}>
                  {key.toUpperCase()}
                </span>
              ))}
            </div>
            {weeks.map((week, weekIndex) => (
              <div role="row" className={styles.gridRow} key={weekIndex}>
                {week.map((day) => {
                  const iso = toIsoDate(day);
                  const isSelected = selected ? isSameDay(day, selected) : false;
                  const outside = day.getMonth() !== viewMonth.getMonth();
                  const disabledDay = disabledByRange(day);
                  return (
                    <span role="gridcell" key={iso} aria-selected={isSelected}>
                      <button
                        type="button"
                        data-date={iso}
                        tabIndex={isSameDay(day, focusDate) ? 0 : -1}
                        className={[
                          styles.day,
                          outside ? styles.dayOutside : '',
                          isSelected ? styles.daySelected : '',
                          isToday(day) ? styles.dayToday : '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                        disabled={disabledDay}
                        aria-label={formatDate(day, locale, 'long')}
                        aria-current={isToday(day) ? 'date' : undefined}
                        onClick={() => commit(day)}
                      >
                        {day.getDate()}
                      </button>
                    </span>
                  );
                })}
              </div>
            ))}
          </div>
          <div className={styles.popoverFooter}>
            <button type="button" className={styles.todayButton} onClick={() => commit(new Date())}>
              {tCommon('actions.today')}
            </button>
            <button
              type="button"
              className={styles.todayButton}
              onClick={() => {
                onChange(null);
                setText('');
                setOpen(false);
                inputRef.current?.focus();
              }}
            >
              {tCommon('actions.clear')}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
