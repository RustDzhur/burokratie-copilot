'use client';

import { createContext, useContext, type ReactNode } from 'react';
import type { SessionUser } from '@/lib/api/types';

const SessionContext = createContext<SessionUser | null>(null);

/** The signed-in person, provided once by the app shell so menus and banners do not each load it again. */
export function SessionProvider({ user, children }: { readonly user: SessionUser | null; readonly children: ReactNode }) {
  return <SessionContext.Provider value={user}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionUser | null {
  return useContext(SessionContext);
}
