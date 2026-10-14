'use client';

import { createContext, useContext, type ReactNode } from 'react';
import type { SessionUser } from '@/lib/api/types';

const SessionContext = createContext<SessionUser | null>(null);
const ReloadContext = createContext<() => void>(() => undefined);

/**
 * The signed-in person, provided once by the app shell so menus and banners do not each load it again. `reload` reads
 * the session again: after the plan or the kind of account changed, the menu follows at once.
 */
export function SessionProvider({
  user,
  reload,
  children,
}: {
  readonly user: SessionUser | null;
  readonly reload?: () => void;
  readonly children: ReactNode;
}) {
  return (
    <SessionContext.Provider value={user}>
      <ReloadContext.Provider value={reload ?? (() => undefined)}>{children}</ReloadContext.Provider>
    </SessionContext.Provider>
  );
}

export function useSession(): SessionUser | null {
  return useContext(SessionContext);
}

export function useSessionReload(): () => void {
  return useContext(ReloadContext);
}
