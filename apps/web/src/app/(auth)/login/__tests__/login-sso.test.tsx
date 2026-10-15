import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen } from '@/test/render';
import LoginPage from '../page';

let query = '';
vi.mock('next/navigation', () => ({
  usePathname: () => '/login',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(query),
}));

function serve(enabled: boolean): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes('/auth/oidc/available')
        ? new Response(JSON.stringify({ enabled }), { status: 200, headers: { 'Content-Type': 'application/json' } })
        : new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    ),
  );
}

describe('Login with an identity provider', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    query = '';
  });

  it('offers single sign-on only when the server says it is set up', async () => {
    serve(true);
    renderWithProviders(<LoginPage />);
    expect(await screen.findByRole('link', { name: 'Mit Single Sign-on anmelden' })).toHaveAttribute('href', '/api/v1/auth/oidc/start');
  });

  it('does not show it when it is not set up, and says so when a sign-in through the provider was refused', async () => {
    serve(false);
    query = 'error=oidc';
    renderWithProviders(<LoginPage />);
    expect(await screen.findByText('Die Anmeldung über den Identitätsanbieter war nicht möglich.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Mit Single Sign-on anmelden' })).not.toBeInTheDocument();
  });
});
