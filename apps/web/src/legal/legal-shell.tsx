'use client';

import Link from 'next/link';
import { createContext, useContext, type ReactNode } from 'react';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { LegalInfo } from '@/lib/api/types';
import { useLocale } from '@/i18n/provider';
import { LEGAL_LINKS } from './links';

const LegalContext = createContext<LegalInfo | null>(null);

export function useLegal(): LegalInfo {
  const value = useContext(LegalContext);
  if (!value) throw new Error('useLegal outside LegalShell');
  return value;
}

/** The operator's own entry; a field nobody entered is shown as missing, never as a made-up value. */
export function Op({ field }: { readonly field: string }) {
  const { operator } = useLegal();
  const value = operator[field];
  return value ? <>{value}</> : <mark data-missing={field}>[noch nicht hinterlegt: {field}]</mark>;
}

/** Whether the operator entered the field. */
export function useHas(field: string): boolean {
  return Boolean(useLegal().operator[field]);
}

const NOTE: Readonly<Record<string, string>> = {
  en: 'The legally binding version of this text is the German one.',
  ru: 'Юридически обязательной является немецкая версия этого текста.',
};

/**
 * Frame of every legal page: the title, the version of the text, the operator's data from the admin area, and a plain notice
 * when that data is still incomplete (the texts are written for the platform; the operator's name and address are not ours to
 * invent).
 */
export function LegalShell({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  const locale = useLocale();
  const { state } = useApiResource((signal) => api.legal.get(signal), []);

  return (
    <article style={{ maxWidth: '72ch', margin: '0 auto', padding: '1rem', lineHeight: 1.6 }} lang="de">
      <h1>{title}</h1>
      {state.status === 'loading' ? <SkeletonText lines={8} /> : null}
      {state.status === 'error' ? <InlineAlert tone="danger">Die Angaben konnten nicht geladen werden. Bitte laden Sie die Seite neu.</InlineAlert> : null}
      {state.status === 'ready' ? (
        <LegalContext.Provider value={state.data}>
          {locale !== 'de' && NOTE[locale] ? <p lang={locale}><em>{NOTE[locale]}</em></p> : null}
          {state.data.missing.length > 0 ? (
            <InlineAlert tone="warning">
              Die Angaben des Betreibers sind noch nicht vollständig hinterlegt (fehlend: {state.data.missing.join(', ')}).
            </InlineAlert>
          ) : null}
          <p><small>Stand: {state.data.version}</small></p>
          {children}
          <LegalLinks />
        </LegalContext.Provider>
      ) : null}
    </article>
  );
}

function LegalLinks() {
  return (
    <nav aria-label="Rechtliche Hinweise" style={{ marginTop: '2rem', display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
      {LEGAL_LINKS.map((link) => (
        <Link key={link.href} href={link.href}>
          {link.label}
        </Link>
      ))}
    </nav>
  );
}

export function H2({ children }: { readonly children: ReactNode }) {
  return <h2 style={{ marginTop: '1.75rem' }}>{children}</h2>;
}
