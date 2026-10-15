'use client';

import { comfortAllowed } from '@/consent/consent';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  APP_TIME_ZONE,
  LOCALE_COOKIE,
  defaultLocale,
  intlLocales,
  localeNames,
  locales,
  type Locale,
} from './config';
import {
  formatCurrency,
  formatDate,
  formatDateTime,
  formatFileSize,
  formatList,
  formatNumber,
  formatPercent,
  formatRelativeTime,
  type DateInput,
  type DateStyleName,
  type TimeStyleName,
} from './format';
import { formatMessage, type MessageValues } from './icu';
import { getCatalogue, type Messages, type Namespace } from './messages';

export type TranslationKey<N extends Namespace> = keyof Messages[N] & string;

export type TFunction<N extends Namespace> = (
  key: TranslationKey<N>,
  values?: MessageValues,
) => string;

/** A translator that is not bound to a single namespace. */
export type GlobalTFunction = <N extends Namespace>(
  namespace: N,
  key: TranslationKey<N>,
  values?: MessageValues,
) => string;

export interface Formatters {
  readonly locale: Locale;
  readonly intlLocale: string;
  readonly timeZone: string;
  readonly date: (value: DateInput, style?: DateStyleName) => string;
  readonly dateTime: (
    value: DateInput,
    options?: { dateStyle?: DateStyleName; timeStyle?: TimeStyleName },
  ) => string;
  readonly number: (value: number, options?: Intl.NumberFormatOptions) => string;
  readonly currency: (value: number, currency?: string) => string;
  readonly percent: (value: number, fractionDigits?: number) => string;
  readonly list: (items: readonly string[]) => string;
  readonly relativeTime: (value: DateInput, base?: Date) => string;
  readonly fileSize: (bytes: number) => string;
}

interface I18nContextValue {
  readonly locale: Locale;
  readonly messages: Messages;
  readonly setLocale: (locale: Locale) => void;
  readonly t: GlobalTFunction;
  readonly formatters: Formatters;
}

const I18nContext = createContext<I18nContextValue | null>(null);

function persistLocale(locale: Locale): void {
  if (typeof document === 'undefined') return;
  document.documentElement.lang = locale;
  // Remembered for the next visit only with the visitor's consent to convenience storage.
  if (!comfortAllowed()) return;
  const maxAge = 60 * 60 * 24 * 365;
  document.cookie = `${LOCALE_COOKIE}=${locale}; path=/; max-age=${maxAge}; samesite=lax`;
}

export interface I18nProviderProps {
  readonly children: ReactNode;
  /** Locale resolved on the server from the locale cookie. */
  readonly initialLocale?: Locale;
}

/**
 * Provides the active locale, its catalogue and formatting helpers.
 *
 * Switching happens client-side without a reload: the cookie is updated for the
 * next server render and `<html lang>` follows immediately (screen readers pick
 * the new language up without a page reload).
 */
export function I18nProvider({ children, initialLocale = defaultLocale }: I18nProviderProps) {
  const [locale, setLocaleState] = useState<Locale>(initialLocale);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    setLocaleState(next);
    persistLocale(next);
  }, []);

  const value = useMemo<I18nContextValue>(() => {
    const messages = getCatalogue(locale);
    const t: GlobalTFunction = (namespace, key, values) => {
      const namespaceMessages = messages[namespace] as Record<string, string | undefined>;
      const pattern = namespaceMessages?.[key as string];
      if (pattern === undefined) {
        if (process.env.NODE_ENV !== 'production') {
          console.warn(
            `[i18n] missing message "${String(namespace)}.${String(key)}" for locale "${locale}"`,
          );
        }
        return '';
      }
      return formatMessage(pattern, values, locale);
    };
    const formatters: Formatters = {
      locale,
      intlLocale: intlLocales[locale],
      timeZone: APP_TIME_ZONE,
      date: (value, style = 'short') => formatDate(value, locale, style),
      dateTime: (value, options) => formatDateTime(value, locale, options),
      number: (value, options) => formatNumber(value, locale, options),
      currency: (value, currency = 'EUR') => formatCurrency(value, locale, currency),
      percent: (value, fractionDigits) => formatPercent(value, locale, fractionDigits),
      list: (items) => formatList(items, locale),
      relativeTime: (value, base) => formatRelativeTime(value, locale, base),
      fileSize: (bytes) => formatFileSize(bytes, locale),
    };
    return { locale, messages, setLocale, t, formatters };
  }, [locale, setLocale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

function useI18nContext(): I18nContextValue {
  const context = useContext(I18nContext);
  if (!context) {
    throw new Error('useTranslations/useLocale must be used inside <I18nProvider>');
  }
  return context;
}

/** Typed translator for one namespace: `const t = useTranslations('inbox')`. */
export function useTranslations<N extends Namespace>(namespace: N): TFunction<N> {
  const { t } = useI18nContext();
  return useCallback<TFunction<N>>((key, values) => t(namespace, key, values), [t, namespace]);
}

/** Translator without a fixed namespace: `t('inbox', 'empty.title')`. */
export function useGlobalTranslations(): GlobalTFunction {
  return useI18nContext().t;
}

export function useLocale(): Locale {
  return useI18nContext().locale;
}

export function useAvailableLocales(): ReadonlyArray<{ locale: Locale; label: string }> {
  return useMemo(() => locales.map((locale) => ({ locale, label: localeNames[locale] })), []);
}

export function useSetLocale(): (locale: Locale) => void {
  return useI18nContext().setLocale;
}

export function useFormatters(): Formatters {
  return useI18nContext().formatters;
}

export function useRawMessages(): Messages {
  return useI18nContext().messages;
}
