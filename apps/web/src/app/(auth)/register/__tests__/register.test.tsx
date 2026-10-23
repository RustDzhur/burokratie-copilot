import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import RegisterPage from '../page';

const push = vi.fn();
vi.mock('next/navigation', () => ({
  usePathname: () => '/register',
  useRouter: () => ({ push, replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const calls: { url: string; method: string; body: string | null }[] = [];

function serve(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({
        url,
        method: init?.method ?? 'GET',
        body: typeof init?.body === 'string' ? init.body : null,
      });
      if (url.includes('/public/plans')) {
        return new Response(
          JSON.stringify({ trialDays: 60, retentionDays: 100, plans: [] }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (url.includes('/auth/register')) {
        return new Response(JSON.stringify({ userId: 'u1' }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

/**
 * §4.3 of the Kanzlei specification: there is one kind of cabinet. The registration asks for a person and a
 * password, and never for a cabinet type — a choice there would be a product the edition no longer is.
 */
describe('registration in the Kanzlei edition', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
    push.mockClear();
  });

  it('asks for no account type at all', async () => {
    serve();
    renderWithProviders(<RegisterPage />);
    expect(screen.getByRole('heading', { name: 'Konto erstellen' })).toBeInTheDocument();

    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Kontotyp' })).not.toBeInTheDocument();
    expect(document.querySelector('input[name="accountType"]')).toBeNull();
  });

  it('creates the account without sending a cabinet type', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<RegisterPage />);

    await user.type(screen.getByLabelText('Vor- und Nachname'), 'Erika Mustermann');
    await user.type(screen.getByLabelText('E-Mail-Adresse'), 'erika@example.de');
    await user.type(screen.getByLabelText('Passwort'), 'EinSicheresPasswort-2026!');
    await user.type(screen.getByLabelText('Passwort wiederholen'), 'EinSicheresPasswort-2026!');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: 'Konto erstellen' }));

    await waitFor(() =>
      expect(calls.some((call) => call.url.includes('/auth/register') && call.method === 'POST')).toBe(true),
    );
    const registration = calls.find((call) => call.url.includes('/auth/register'));
    expect(registration?.body).not.toBeNull();
    expect(JSON.parse(registration?.body ?? '{}')).not.toHaveProperty('accountType');
    await waitFor(() => expect(push).toHaveBeenCalledWith('/verify-email'));
  });
});
