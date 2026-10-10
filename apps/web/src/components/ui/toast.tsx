'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { Icon, type IconName } from '@/components/icons';
import { useTranslations } from '@/i18n/provider';
import type { Tone } from './feedback';
import styles from './toast.module.css';

export interface ToastAction {
  readonly label: string;
  readonly onClick: () => void;
}

export interface ToastOptions {
  readonly title: string;
  readonly description?: string;
  readonly tone?: Tone;
  /** `0` keeps the toast until it is dismissed. Defaults to 6000 ms. */
  readonly durationMs?: number;
  readonly action?: ToastAction;
}

interface ToastRecord extends ToastOptions {
  readonly id: string;
}

export interface ToastApi {
  readonly show: (options: ToastOptions) => string;
  readonly success: (title: string, description?: string) => string;
  readonly error: (title: string, description?: string) => string;
  readonly info: (title: string, description?: string) => string;
  readonly warning: (title: string, description?: string) => string;
  readonly dismiss: (id: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const TONE_ICONS: Record<Tone, IconName> = {
  neutral: 'info',
  info: 'info',
  success: 'check-circle',
  warning: 'alert-triangle',
  danger: 'alert-circle',
  accent: 'sparkles',
};

function createToastId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `toast-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Toast host plus screen-reader announcements.
 *
 * Toasts are announced through live regions: errors use `role="alert"`
 * (assertive), everything else `role="status"` (polite), so asynchronous
 * results are announced without stealing focus (docs/task.md §23.2).
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<readonly ToastRecord[]>([]);
  const timers = useRef(new Map<string, number>());
  const t = useTranslations('common');

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
    const timer = timers.current.get(id);
    if (timer !== undefined) {
      window.clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const show = useCallback(
    (options: ToastOptions): string => {
      const id = createToastId();
      const durationMs = options.durationMs ?? 6000;
      setToasts((current) => [...current, { ...options, id }]);
      if (durationMs > 0) {
        const timer = window.setTimeout(() => dismiss(id), durationMs);
        timers.current.set(id, timer);
      }
      return id;
    },
    [dismiss],
  );

  useEffect(
    () => () => {
      for (const timer of timers.current.values()) window.clearTimeout(timer);
      timers.current.clear();
    },
    [],
  );

  const api = useMemo<ToastApi>(
    () => ({
      show,
      dismiss,
      success: (title, description) => show({ title, description, tone: 'success' }),
      error: (title, description) => show({ title, description, tone: 'danger', durationMs: 0 }),
      info: (title, description) => show({ title, description, tone: 'info' }),
      warning: (title, description) => show({ title, description, tone: 'warning' }),
    }),
    [show, dismiss],
  );

  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  return (
    <ToastContext.Provider value={api}>
      {children}
      {mounted && toasts.length > 0
        ? createPortal(
            <div className={styles.region}>
              {toasts.map((toast) => {
                const tone = toast.tone ?? 'info';
                return (
                  <div
                    key={toast.id}
                    className={[styles.toast, styles[`toast-${tone}`]].join(' ')}
                    role={tone === 'danger' ? 'alert' : 'status'}
                    aria-live={tone === 'danger' ? 'assertive' : 'polite'}
                  >
                    <span className={styles.icon} aria-hidden="true">
                      <Icon name={TONE_ICONS[tone]} size={18} />
                    </span>
                    <div className={styles.body}>
                      <p className={styles.title}>{toast.title}</p>
                      {toast.description ? (
                        <p className={styles.description}>{toast.description}</p>
                      ) : null}
                      {toast.action ? (
                        <button
                          type="button"
                          className={styles.action}
                          onClick={toast.action.onClick}
                        >
                          {toast.action.label}
                        </button>
                      ) : null}
                    </div>
                    <button
                      type="button"
                      className={styles.close}
                      onClick={() => dismiss(toast.id)}
                      aria-label={t('actions.dismiss')}
                    >
                      <Icon name="x" size={15} />
                    </button>
                  </div>
                );
              })}
            </div>,
            document.body,
          )
        : null}
    </ToastContext.Provider>
  );
}

const noopToast: ToastApi = {
  show: () => '',
  success: () => '',
  error: () => '',
  info: () => '',
  warning: () => '',
  dismiss: () => undefined,
};

/** Safe outside a provider (e.g. in isolated unit tests) — returns a no-op API. */
export function useToast(): ToastApi {
  return useContext(ToastContext) ?? noopToast;
}
