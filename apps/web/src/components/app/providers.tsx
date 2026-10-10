'use client';

import type { ReactNode } from 'react';
import { ToastProvider } from '@/components/ui/toast';
import { I18nProvider } from '@/i18n/provider';
import type { Locale } from '@/i18n/config';
import { AppearanceProvider } from '@/settings/appearance-provider';
import type { AppearanceSettings } from '@/settings/appearance';

export interface AppProvidersProps {
  readonly children: ReactNode;
  readonly initialLocale: Locale;
  readonly initialAppearance: AppearanceSettings;
}

/** Single composition point for locale, appearance and toast hosts. */
export function AppProviders({ children, initialLocale, initialAppearance }: AppProvidersProps) {
  return (
    <I18nProvider initialLocale={initialLocale}>
      <AppearanceProvider initialSettings={initialAppearance}>
        <ToastProvider>{children}</ToastProvider>
      </AppearanceProvider>
    </I18nProvider>
  );
}
