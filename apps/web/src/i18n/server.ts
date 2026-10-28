import { cookies } from 'next/headers';
import { LOCALE_COOKIE, defaultLocale, toLocale, type Locale } from './config';
import { formatMessage, type MessageValues } from './icu';
import { getCatalogue, type Messages, type Namespace } from './messages';

/**
 * Server-side locale resolution. The locale cookie is written by the client
 * provider, so the first paint already uses the right catalogue (no language
 * flash) and `<html lang>` is correct for screen readers and crawlers.
 */
export async function getServerLocale(): Promise<Locale> {
  const cookieStore = await cookies();
  const value = cookieStore.get(LOCALE_COOKIE)?.value;
  const fromEnv = process.env.NEXT_PUBLIC_DEFAULT_LOCALE;
  return value ? toLocale(value, defaultLocale) : toLocale(fromEnv, defaultLocale);
}

export interface ServerTranslator {
  readonly locale: Locale;
  readonly messages: Messages;
  readonly t: <N extends Namespace>(
    namespace: N,
    key: keyof Messages[N] & string,
    values?: MessageValues,
  ) => string;
}

/** Translator for server components; mirrors the client `t()` semantics. */
export async function getServerTranslations(): Promise<ServerTranslator> {
  const locale = await getServerLocale();
  const messages = getCatalogue(locale);
  return {
    locale,
    messages,
    t: (namespace, key, values) => {
      const pattern = (messages[namespace] as Record<string, string | undefined>)[key as string];
      if (pattern === undefined) return '';
      return formatMessage(pattern, values, locale);
    },
  };
}
