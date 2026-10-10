import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent } from '@/test/render';
import { Button, IconButton, buttonClass } from '../button';

describe('Button', () => {
  it('renders its label and handles clicks', async () => {
    const onClick = vi.fn();
    renderWithProviders(<Button onClick={onClick}>Speichern</Button>);

    const button = screen.getByRole('button', { name: 'Speichern' });
    await userEvent.click(button);

    expect(onClick).toHaveBeenCalledTimes(1);
    expect(button).toHaveAttribute('type', 'button');
  });

  it('exposes variant and size through data attributes', () => {
    renderWithProviders(
      <>
        <Button variant="primary" size="lg">
          Groß
        </Button>
        <Button variant="danger">Gefährlich</Button>
      </>,
    );

    expect(screen.getByRole('button', { name: 'Groß' })).toHaveAttribute('data-variant', 'primary');
    expect(screen.getByRole('button', { name: 'Groß' })).toHaveAttribute('data-size', 'lg');
    expect(screen.getByRole('button', { name: 'Gefährlich' })).toHaveAttribute(
      'data-variant',
      'danger',
    );
  });

  it('blocks interaction and announces busy state while loading', async () => {
    const onClick = vi.fn();
    renderWithProviders(
      <Button loading loadingLabel="Wird gespeichert" onClick={onClick}>
        Speichern
      </Button>,
    );

    const button = screen.getByRole('button', { name: /Speichern/ });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Wird gespeichert')).toBeInTheDocument();

    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('ignores clicks when disabled', async () => {
    const onClick = vi.fn();
    renderWithProviders(
      <Button disabled onClick={onClick}>
        Gesperrt
      </Button>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Gesperrt' }));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('is reachable and activatable with the keyboard only', async () => {
    const onClick = vi.fn();
    renderWithProviders(<Button onClick={onClick}>Weiter</Button>);

    await userEvent.tab();
    expect(screen.getByRole('button', { name: 'Weiter' })).toHaveFocus();

    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard(' ');
    expect(onClick).toHaveBeenCalledTimes(2);
  });

  it('renders icon slots', () => {
    const { container } = renderWithProviders(<Button iconLeft="plus">Neu</Button>);
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('exposes styling for link elements through buttonClass', () => {
    const className = buttonClass({ variant: 'primary', size: 'sm', fullWidth: true });
    expect(className).toContain('button');
    expect(className.split(' ').length).toBeGreaterThanOrEqual(4);
  });
});

describe('IconButton', () => {
  it('requires and applies an accessible name', () => {
    renderWithProviders(<IconButton label="Schließen" icon="x" />);

    const button = screen.getByRole('button', { name: 'Schließen' });
    expect(button).toHaveAttribute('aria-label', 'Schließen');
    expect(button).toHaveAttribute('title', 'Schließen');
  });

  it('supports pressed state for toggles', () => {
    renderWithProviders(<IconButton label="Ansicht" icon="grid" pressed />);
    expect(screen.getByRole('button', { name: 'Ansicht' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('reports busy and blocks clicks while loading', async () => {
    const onClick = vi.fn();
    renderWithProviders(
      <IconButton label="Aktualisieren" icon="refresh" loading onClick={onClick} />,
    );

    const button = screen.getByRole('button', { name: 'Aktualisieren' });
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});
