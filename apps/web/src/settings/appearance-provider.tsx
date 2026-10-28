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
  APPEARANCE_COOKIE,
  APPEARANCE_STORAGE_KEY,
  appearanceAttributes,
  defaultAppearance,
  parseAppearance,
  serializeAppearance,
  type AppearanceSettings,
} from './appearance';

interface AppearanceContextValue {
  readonly settings: AppearanceSettings;
  readonly resolvedTheme: 'light' | 'dark';
  readonly update: (patch: Partial<AppearanceSettings>) => void;
  readonly reset: () => void;
}

const AppearanceContext = createContext<AppearanceContextValue | null>(null);

function prefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

/** Writes settings to <html>, localStorage and the cookie used by the server render. */
export function applyAppearance(settings: AppearanceSettings): void {
  if (typeof document === 'undefined') return;
  const attributes = appearanceAttributes(settings, prefersDark(), prefersReducedMotion());
  for (const [name, value] of Object.entries(attributes)) {
    document.documentElement.setAttribute(name, value);
  }
  // The look of the page is applied now; it is kept for the next visit only if the visitor agreed to convenience storage.
  if (!comfortAllowed()) return;
  try {
    localStorage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // Storage may be unavailable (private mode); the cookie below still applies.
  }
  const maxAge = 60 * 60 * 24 * 365;
  document.cookie = `${APPEARANCE_COOKIE}=${serializeAppearance(settings)}; path=/; max-age=${maxAge}; samesite=lax`;
}

export interface AppearanceProviderProps {
  readonly children: ReactNode;
  /** Settings resolved on the server from the appearance cookie. */
  readonly initialSettings?: AppearanceSettings;
}

export function AppearanceProvider({
  children,
  initialSettings = defaultAppearance,
}: AppearanceProviderProps) {
  const [settings, setSettings] = useState<AppearanceSettings>(initialSettings);

  // Pick up settings stored in the browser that the server could not know about
  // (e.g. first visit after the cookie expired, or a second tab).
  useEffect(() => {
    let stored: AppearanceSettings | undefined;
    try {
      const raw = localStorage.getItem(APPEARANCE_STORAGE_KEY);
      stored = raw ? parseAppearance(JSON.parse(raw) as unknown) : undefined;
    } catch {
      stored = undefined;
    }
    if (stored) {
      setSettings(stored);
      applyAppearance(stored);
    } else {
      applyAppearance(initialSettings);
    }
  }, [initialSettings]);

  // React to OS-level changes while the preference is "system"/"auto".
  useEffect(() => {
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    const handler = (): void => applyAppearance(settings);
    query.addEventListener('change', handler);
    motionQuery.addEventListener('change', handler);
    return () => {
      query.removeEventListener('change', handler);
      motionQuery.removeEventListener('change', handler);
    };
  }, [settings]);

  const update = useCallback((patch: Partial<AppearanceSettings>) => {
    setSettings((current) => {
      const next = { ...current, ...patch };
      applyAppearance(next);
      return next;
    });
  }, []);

  const reset = useCallback(() => {
    setSettings(defaultAppearance);
    applyAppearance(defaultAppearance);
  }, []);

  const value = useMemo<AppearanceContextValue>(
    () => ({
      settings,
      resolvedTheme: appearanceAttributes(settings, prefersDark(), false)['data-theme'],
      update,
      reset,
    }),
    [settings, update, reset],
  );

  return <AppearanceContext.Provider value={value}>{children}</AppearanceContext.Provider>;
}

export function useAppearance(): AppearanceContextValue {
  const context = useContext(AppearanceContext);
  if (!context) {
    throw new Error('useAppearance must be used inside <AppearanceProvider>');
  }
  return context;
}
