import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  expectNoA11yViolations,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  within,
} from '@/test/render';
import { AppShell } from '../app-shell';

vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * The shell is tested against an unreachable backend on purpose: navigation,
 * landmarks and keyboard access must work even when no data can be loaded
 * (docs/task.md §29 — no blank screens, graceful degradation).
 */
describe('AppShell', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders skip link, sidebar, topbar and main landmark', async () => {
    renderWithProviders(
      <AppShell>
        <h1>Dashboard</h1>
      </AppShell>,
    );

    expect(screen.getByRole('link', { name: 'Zum Hauptinhalt springen' })).toHaveAttribute(
      'href',
      '#main-content',
    );
    expect(screen.getByRole('navigation', { name: 'Hauptnavigation' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument();
  });

  it('keeps the shell usable when the session cannot be loaded', async () => {
    renderWithProviders(
      <AppShell>
        <h1>Dashboard</h1>
      </AppShell>,
    );

    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' });
    // The Kanzlei menu is eleven items plus the brand link back to the dashboard - still within the
    // specification's limit (§11.3: at most twelve items in three groups), not a snapshot of a longer menu.
    expect(within(nav).getAllByRole('link')).toHaveLength(12);
    // No fabricated user data: the account button falls back to the sign-in label.
    expect(screen.getAllByRole('button', { name: /Anmelden|Benutzermenü/ }).length).toBeGreaterThan(
      0,
    );
  });

  it('offers the command palette through the topbar search button', async () => {
    renderWithProviders(
      <AppShell>
        <h1>Dashboard</h1>
      </AppShell>,
    );

    await userEvent.click(screen.getByRole('button', { name: /Suchen oder Befehl ausführen/ }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('combobox')).toBeInTheDocument();
    expect(within(dialog).getAllByRole('option').length).toBeGreaterThan(10);
  });

  it('opens the command palette with Ctrl+K', async () => {
    renderWithProviders(
      <AppShell>
        <h1>Dashboard</h1>
      </AppShell>,
    );

    await userEvent.keyboard('{Control>}k{/Control}');
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
  });

  it('has no detectable accessibility violations', async () => {
    const { container } = renderWithProviders(
      <AppShell>
        <h1>Dashboard</h1>
      </AppShell>,
    );

    await waitFor(() => expect(screen.getByRole('main')).toBeInTheDocument());
    await expectNoA11yViolations(container);
  });
});
