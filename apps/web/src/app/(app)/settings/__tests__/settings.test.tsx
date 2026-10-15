import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen } from '@/test/render';
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
    };
    for (const [name, href] of Object.entries(expected)) {
      expect(screen.getByRole('link', { name }), name).toHaveAttribute('href', href);
    }
  });
});
