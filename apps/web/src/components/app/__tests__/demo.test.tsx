import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import type { SessionUser } from '@/lib/api/types';
import { DemoBanner } from '../demo-banner';
import { DemoDialog } from '../demo-dialog';
import { GuidedTour } from '../guided-tour';

const push = vi.fn();
const replace = vi.fn();
vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push, replace, prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const calls: { url: string; method: string; body: string | null }[] = [];

function serve(handlers: Record<string, unknown> = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : null });
      const key = Object.keys(handlers).find((k) => url.includes(k));
      return new Response(JSON.stringify(key ? handlers[key] : {}), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

const demoUser = { id: 'u', name: 'Erika Mustermann', email: 'demo-abc@demo.invalid', roles: [], workspaceId: 'w', workspaceName: 'Demo', workspaceType: 'company' } as unknown as SessionUser;
const realUser = { ...demoUser, email: 'erika@example.de' } as SessionUser;

describe('demo cabinet interface', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
    push.mockClear();
    replace.mockClear();
  });

  it('asks what the visitor wants to use the platform for and opens the chosen cabinet', async () => {
    const user = userEvent.setup();
    serve({ '/public/demo/start': { persona: 'FREELANCER', next: '/dashboard?tour=1' } });
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    renderWithProviders(<DemoDialog open onClose={vi.fn()} />);

    for (const name of [/Einzelperson/, /Familie/, /Freiberufler/, /Unternehmen/, /Beratungsstelle/]) {
      expect(screen.getByRole('radio', { name })).toBeInTheDocument();
    }
    await user.click(screen.getByRole('radio', { name: /Freiberufler/ }));
    await user.click(screen.getByRole('button', { name: 'Weiter' }));

    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/public/demo/start') && (c.body ?? '').includes('"persona":"FREELANCER"'))).toBe(true));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('/dashboard?tour=1'));
  });

  it('shows the demo strip only in a demo cabinet, and ending the demo deletes it and goes to the start page', async () => {
    const user = userEvent.setup();
    serve({ '/demo/status': { demo: true, persona: 'COMPANY', expiresAt: '2030-01-01T00:00:00Z' } });
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const { container, rerender } = renderWithProviders(<DemoBanner user={realUser} />);
    expect(container).toBeEmptyDOMElement();
    expect(calls).toHaveLength(0);

    rerender(<DemoBanner user={demoUser} />);
    expect(await screen.findByText('Demo-Kabinett')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Demo beenden' }));
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/demo/exit') && c.method === 'POST')).toBe(true));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('/'));
  });

  it('the tour explains each stop, can go back, and can be left at any time', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(<GuidedTour persona="FREELANCER" onClose={onClose} />);

    expect(await screen.findByRole('dialog', { name: 'Willkommen im Demo-Kabinett' })).toBeInTheDocument();
    expect(screen.getByText(/Schritt 1 von/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Weiter' }));
    expect(screen.getByRole('dialog', { name: 'Was jetzt Aufmerksamkeit braucht' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Zurück' }));
    expect(screen.getByRole('dialog', { name: 'Willkommen im Demo-Kabinett' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('the tour points at the real element when it is on the screen', async () => {
    const target = document.createElement('a');
    target.setAttribute('href', '/tasks');
    target.textContent = 'Aufgaben';
    const nav = document.createElement('nav');
    nav.appendChild(target);
    document.body.appendChild(nav);
    target.getClientRects = () => [{ top: 10, left: 10, width: 80, height: 20 } as DOMRect] as unknown as DOMRectList;
    target.getBoundingClientRect = () => ({ top: 10, left: 10, width: 80, height: 20, right: 90, bottom: 30, x: 10, y: 10, toJSON: () => ({}) });
    target.scrollIntoView = vi.fn();

    const user = userEvent.setup();
    renderWithProviders(<GuidedTour persona="PERSON" onClose={vi.fn()} />);
    for (let i = 0; i < 5; i += 1) await user.click(await screen.findByRole('button', { name: 'Weiter' }));
    expect(await screen.findByRole('dialog', { name: 'Aufgaben' })).toBeInTheDocument();
    await waitFor(() => expect(target.scrollIntoView).toHaveBeenCalled());
    nav.remove();
  });
});
