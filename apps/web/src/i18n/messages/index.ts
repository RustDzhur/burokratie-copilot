import { defaultLocale, type Locale } from '../config';
import { deCore, type CoreMessages } from './core/de';
import { enCore } from './core/en';
import { ruCore } from './core/ru';
import { deScreens, type ScreenMessages } from './screens/de';
import { enScreens } from './screens/en';
import { ruScreens } from './screens/ru';

/**
 * `Messages` is derived from the German catalogues, which are the source of
 * truth. The English and Russian catalogues are typed with it, therefore a
 * missing or stray key breaks the build instead of silently falling back.
 */
export type Messages = CoreMessages & ScreenMessages;

export type Namespace = keyof Messages;

const de: Messages = { ...deCore, ...deScreens };
const en: Messages = { ...enCore, ...enScreens };
const ru: Messages = { ...ruCore, ...ruScreens };

export const catalogues: Record<Locale, Messages> = { de, en, ru };

export function getCatalogue(locale: Locale): Messages {
  return catalogues[locale] ?? catalogues[defaultLocale];
}

/** Exposed for tests and tooling: every namespace shipped by the client. */
export const namespaces = Object.keys(de) as Namespace[];
