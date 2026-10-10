/**
 * Locale configuration for the web client.
 *
 * German is the primary, most complete language (see docs/task.md §24).
 * English and Russian are full catalogues as well: the `Messages` type is
 * derived from the German catalogue, so a missing English/Russian key is a
 * compile-time error.
 */

export const locales = ['de', 'en', 'ru'] as const;

export type Locale = (typeof locales)[number];

export const defaultLocale: Locale = 'de';

/** Cookie used for the server-side render of the active locale. */
export const LOCALE_COOKIE = 'bc_locale';

/** localStorage key kept in sync so client-side switches survive reloads. */
export const LOCALE_STORAGE_KEY = 'bc.locale';

export const localeNames: Record<Locale, string> = {
  de: 'Deutsch',
  en: 'English',
  ru: 'Русский',
};

/** BCP-47 tags handed to the Intl APIs. */
export const intlLocales: Record<Locale, string> = {
  de: 'de-DE',
  en: 'en-GB',
  ru: 'ru-RU',
};

/** Administrative dates are always rendered in the German time zone. */
export const APP_TIME_ZONE = 'Europe/Berlin';

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (locales as readonly string[]).includes(value);
}

export function toLocale(value: unknown, fallback: Locale = defaultLocale): Locale {
  return isLocale(value) ? value : fallback;
}
