import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { CommandPalette, type CommandItem } from '../command-palette';

const push = vi.fn();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  usePathname: () => '/dashboard',
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
}));

const ITEMS: readonly CommandItem[] = [
  { id: 'dashboard', label: 'Dashboard', group: 'Navigation', href: '/dashboard' },
  { id: 'cases', label: 'Vorgänge und Akten', group: 'Navigation', href: '/cases' },
  {
    id: 'accessibility',
    label: 'Barrierefreiheit',
    group: 'Befehle',
    href: '/accessibility',
    keywords: 'kontrast schriftgröße',
  },
];

describe('CommandPalette', () => {
  it('renders nothing when closed', () => {
    renderWithProviders(<CommandPalette open={false} onClose={() => undefined} items={ITEMS} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('exposes a combobox inside a modal dialog and focuses the input', async () => {
    renderWithProviders(<CommandPalette open onClose={() => undefined} items={ITEMS} />);

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');

    const combobox = screen.getByRole('combobox');
    expect(combobox).toHaveAttribute('aria-expanded', 'true');
    await waitFor(() => expect(combobox).toHaveFocus());
    expect(screen.getAllByRole('option')).toHaveLength(3);
  });

  it('filters by label, group and keywords', async () => {
    renderWithProviders(<CommandPalette open onClose={() => undefined} items={ITEMS} />);

    await userEvent.type(screen.getByRole('combobox'), 'akten');
    expect(screen.getAllByRole('option')).toHaveLength(1);
    expect(screen.getByRole('option')).toHaveAccessibleName(/Vorgänge und Akten/);

    await userEvent.clear(screen.getByRole('combobox'));
    await userEvent.type(screen.getByRole('combobox'), 'kontrast');
    expect(screen.getAllByRole('option')).toHaveLength(1);
    expect(screen.getByRole('option')).toHaveAccessibleName(/Barrierefreiheit/);
  });

  it('shows an explanatory empty state', async () => {
    renderWithProviders(<CommandPalette open onClose={() => undefined} items={ITEMS} />);

    await userEvent.type(screen.getByRole('combobox'), 'zzz');
    expect(screen.getByText(/Keine Treffer/)).toBeInTheDocument();
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });

  it('moves the active option with the arrow keys and navigates on Enter', async () => {
    const onClose = vi.fn();
    renderWithProviders(<CommandPalette open onClose={onClose} items={ITEMS} />);

    const combobox = screen.getByRole('combobox');
    await userEvent.type(combobox, '{ArrowDown}');
    expect(combobox.getAttribute('aria-activedescendant')).toContain('cases');

    await userEvent.keyboard('{Enter}');
    expect(onClose).toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith('/cases');
  });

  it('closes on Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(<CommandPalette open onClose={onClose} items={ITEMS} />);

    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });
});
