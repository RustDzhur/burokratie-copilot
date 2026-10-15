import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent } from '@/test/render';
import type { SessionUser } from '@/lib/api/types';
import { Topbar } from '../topbar';

const push = vi.fn();
vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push, replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const user = {
  id: 'u1', name: 'Erika Weber', email: 'erika@example.de', roles: [], workspaceId: 'w1', workspaceName: 'Weber',
  workspaceType: 'company', avatarUrl: '/api/v1/profile/avatar?v=2',
} as unknown as SessionUser;

describe('Topbar: account menu', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    push.mockClear();
  });

  it('opens the profile and the account settings, and shows the person\'s photo', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [], capabilities: { canCreateClients: false, professionalPlan: false } }), { status: 200, headers: { 'Content-Type': 'application/json' } })));
    const person = userEvent.setup();
    const { container } = renderWithProviders(<Topbar onOpenSearch={vi.fn()} onOpenMobileNav={vi.fn()} user={user} />);

    expect(container.querySelector('img[src="/api/v1/profile/avatar?v=2"]')).not.toBeNull();
    await person.click(screen.getByRole('button', { name: /Erika Weber/ }));
    await person.click(await screen.findByRole('menuitem', { name: 'Profil' }));
    expect(push).toHaveBeenCalledWith('/profile');

    await person.click(screen.getByRole('button', { name: /Erika Weber/ }));
    await person.click(await screen.findByRole('menuitem', { name: 'Kontoeinstellungen' }));
    expect(push).toHaveBeenCalledWith('/settings');

    await person.click(screen.getByRole('button', { name: /Erika Weber/ }));
    expect(await screen.findByRole('link', { name: 'Kontoeinstellungen' })).toHaveAttribute('href', '/settings');
  });
});
