import type { ReactNode } from 'react';
import { AppShell } from '@/components/app/app-shell';

/**
 * Workspace layout: every authenticated screen renders inside the application
 * shell (skip link, sidebar, topbar, main landmark, command palette).
 */
export default function WorkspaceLayout({ children }: { children: ReactNode }) {
  return <AppShell>{children}</AppShell>;
}
