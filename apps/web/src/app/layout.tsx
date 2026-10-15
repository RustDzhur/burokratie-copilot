import type { Metadata, Viewport } from 'next';
import type { ReactNode } from 'react';
import '@/styles/tokens.css';
import '@/styles/globals.css';
import { AppProviders } from '@/components/app/providers';
import { CookieConsent } from '@/components/app/cookie-consent';
import { ServiceWorkerRegistrar } from '@/components/app/service-worker';
import { getServerLocale } from '@/i18n/server';
import {
  APPEARANCE_COOKIE,
  appearanceBootstrapScript,
  defaultAppearance,
  parseAppearanceCookie,
} from '@/settings/appearance';
import { cookies } from 'next/headers';

export const metadata: Metadata = {
  title: {
    default: 'Bürokratie-Copilot',
    template: '%s · Bürokratie-Copilot',
  },
  description:
    'Bürokratie-Copilot bündelt Dokumente, Vorgänge, Fristen und Zahlungen in einem sicheren Arbeitsbereich.',
  applicationName: 'Bürokratie-Copilot',
  formatDetection: { telephone: false },
  robots: { index: false, follow: false },
  // What makes the app installable on a phone. Without the link the manifest is never read.
  manifest: '/manifest.webmanifest',
  appleWebApp: { capable: true, title: 'Bürokratie-Copilot', statusBarStyle: 'default' },
  icons: {
    icon: [
      { url: '/favicon.ico', sizes: 'any' },
      { url: '/icons/favicon-48.png', sizes: '48x48', type: 'image/png' },
      { url: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
    ],
    apple: '/icons/apple-touch-icon.png',
  },
  openGraph: {
    title: 'Bürokratie-Copilot',
    description: 'Weniger Bürokratie. Mehr Zeit für dich.',
    images: [{ url: '/brand/og-image.png', width: 1200, height: 630, alt: 'Bürokratie-Copilot' }],
  },
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#f4f6fb' },
    { media: '(prefers-color-scheme: dark)', color: '#070b14' },
  ],
};

/**
 * Root layout.
 *
 * Locale and appearance preferences are read from cookies on the server so the
 * first paint is already in the right language and theme (`<html lang>` and the
 * data attributes are correct before hydration). The inline bootstrap script
 * applies stored preferences before the first paint to avoid a theme flash.
 */
export default async function RootLayout({ children }: { children: ReactNode }) {
  const locale = await getServerLocale();
  const cookieStore = await cookies();
  const appearance =
    parseAppearanceCookie(cookieStore.get(APPEARANCE_COOKIE)?.value) ?? defaultAppearance;

  return (
    <html lang={locale} data-theme="light" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: appearanceBootstrapScript() }} />
      </head>
      <body>
        <AppProviders initialLocale={locale} initialAppearance={appearance}>
        <ServiceWorkerRegistrar />
          {children}
          <CookieConsent />
        </AppProviders>
      </body>
    </html>
  );
}
