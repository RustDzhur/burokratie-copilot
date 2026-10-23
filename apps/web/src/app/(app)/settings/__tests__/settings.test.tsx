import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen } from '@/test/render';
import { SessionProvider } from '@/lib/auth/session-context';
import AccountSettingsPage from '../page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/settings',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

describe('AccountSettingsPage', () => {
  it('leads to every setting a person can change, each a working link', () => {
    renderWithProviders(<AccountSettingsPage />);
    const expected: Record<string, string> = {
      'Mein Profil': '/profile',
      Sicherheit: '/security',
      Barrierefreiheit: '/accessibility',
      Benachrichtigungen: '/notifications',
      'Tarif und Abrechnung': '/billing',
      Regeln: '/rules',
      Datumsbegriffe: '/settings/date-markers',
      'Konten-Zuordnung': '/settings/konto-mapping',
      'Mandanten-Zuordnung': '/settings/mandant-rules',
    };
    for (const [name, href] of Object.entries(expected)) {
      expect(screen.getByRole('link', { name }), name).toHaveAttribute('href', href);
    }
  });

  it('hides the date vocabulary from a person who may not see settings', () => {
    const user = {
      id: 'u',
      name: 'N',
      email: 'n@example.de',
      roles: [],
      workspaceId: 'w',
      workspaceName: 'W',
      permissions: ['tasks.view'],
    };
    const { unmount } = renderWithProviders(
      <SessionProvider user={user}>
        <AccountSettingsPage />
      </SessionProvider>,
    );
    expect(screen.queryByRole('link', { name: 'Datumsbegriffe' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Konten-Zuordnung' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Mandanten-Zuordnung' })).toBeNull();
    unmount();

    renderWithProviders(
      <SessionProvider user={{ ...user, permissions: ['settings.view'] }}>
        <AccountSettingsPage />
      </SessionProvider>,
    );
    expect(screen.getByRole('link', { name: 'Datumsbegriffe' })).toHaveAttribute(
      'href',
      '/settings/date-markers',
    );
    expect(screen.getByRole('link', { name: 'Konten-Zuordnung' })).toHaveAttribute(
      'href',
      '/settings/konto-mapping',
    );
    expect(screen.getByRole('link', { name: 'Mandanten-Zuordnung' })).toHaveAttribute(
      'href',
      '/settings/mandant-rules',
    );
  });
});
