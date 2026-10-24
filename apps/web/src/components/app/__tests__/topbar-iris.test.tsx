import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent } from '@/test/render';
import { Topbar } from '../topbar';

vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

describe('Topbar: Iris', () => {
  const props = { onOpenSearch: vi.fn(), onOpenMobileNav: vi.fn(), user: null };

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('opens the assistant panel from the topbar button and marks it as expanded', async () => {
    const person = userEvent.setup();
    renderWithProviders(<Topbar {...props} />);

    const button = screen.getByRole('button', { name: 'Iris öffnen' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveAttribute('aria-controls', 'iris-panel');

    await person.click(button);
    expect(screen.getByRole('dialog', { name: 'Iris' })).toBeInTheDocument();
    expect(button).toHaveAttribute('aria-expanded', 'true');
  });

  it('closes the panel with the close button', async () => {
    const person = userEvent.setup();
    renderWithProviders(<Topbar {...props} />);

    await person.click(screen.getByRole('button', { name: 'Iris öffnen' }));
    expect(screen.getByRole('dialog', { name: 'Iris' })).toBeInTheDocument();

    await person.click(screen.getByRole('button', { name: 'Iris schließen' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('closes on Escape', async () => {
    const person = userEvent.setup();
    renderWithProviders(<Topbar {...props} />);

    await person.click(screen.getByRole('button', { name: 'Iris öffnen' }));
    expect(screen.getByRole('dialog', { name: 'Iris' })).toBeInTheDocument();

    await person.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('hides the Iris button for a workspace whose access ended', () => {
    renderWithProviders(<Topbar {...props} blocked />);
    expect(screen.queryByRole('button', { name: 'Iris öffnen' })).toBeNull();
  });
});
