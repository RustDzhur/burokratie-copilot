import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import ProfilePage from '../page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/profile',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const calls: { url: string; method: string; body: unknown }[] = [];

function serve(avatarVersion: number | null): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body ?? null });
      const body = url.endsWith('/profile') && (init?.method ?? 'GET') === 'GET'
        ? { email: 'erika@example.de', displayName: 'Erika Weber', locale: 'de', timeZone: 'Europe/Berlin', avatarVersion }
        : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('ProfilePage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows the own data, keeps the e-mail read-only and saves the changed name, language and time zone', async () => {
    const user = userEvent.setup();
    serve(null);
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    renderWithProviders(<ProfilePage />);

    const email = await screen.findByLabelText('E-Mail-Adresse');
    expect(email).toHaveValue('erika@example.de');
    expect(email).toHaveAttribute('readonly');

    const name = screen.getByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Erika Müller');
    await user.selectOptions(screen.getByLabelText('Sprache'), 'ru');
    await user.click(screen.getByRole('button', { name: 'Speichern' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT' && call.url.endsWith('/profile')
      && String(call.body).includes('Erika Müller') && String(call.body).includes('"locale":"ru"'))).toBe(true));
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('refuses a name that is too short without asking the server', async () => {
    const user = userEvent.setup();
    serve(null);
    renderWithProviders(<ProfilePage />);

    const name = await screen.findByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'E');
    await user.click(screen.getByRole('button', { name: 'Speichern' }));

    expect(screen.getByText('Bitte geben Sie einen Namen mit mindestens 2 Zeichen an.')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);
  });

  it('sends a chosen photo, and offers to remove one that exists', async () => {
    const user = userEvent.setup();
    serve(3);
    vi.stubGlobal('location', { ...window.location, reload: vi.fn() });
    renderWithProviders(<ProfilePage />);

    expect(await screen.findByRole('button', { name: 'Entfernen' })).toBeInTheDocument();
    const file = new File([new Uint8Array([1, 2, 3])], 'me.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('Foto wählen'), file);

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT' && call.url.endsWith('/profile/avatar'))).toBe(true));
    await user.click(screen.getByRole('button', { name: 'Entfernen' }));
    await waitFor(() => expect(calls.some((call) => call.method === 'DELETE' && call.url.endsWith('/profile/avatar'))).toBe(true));
  });
});
