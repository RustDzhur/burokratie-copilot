import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, within } from '@/test/render';
import { ConfirmDialog, Dialog, Drawer, Tooltip } from '../overlay';
import { Button } from '../button';

describe('Dialog', () => {
  it('renders as an accessible modal dialog with label and description', () => {
    renderWithProviders(
      <Dialog open onClose={() => undefined} title="Vorgang anlegen" description="Bitte ausfüllen">
        <p>Inhalt</p>
      </Dialog>,
    );

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('Vorgang anlegen');
    expect(dialog).toHaveAccessibleDescription('Bitte ausfüllen');
    expect(within(dialog).getByText('Inhalt')).toBeInTheDocument();
  });

  it('renders nothing while closed', () => {
    renderWithProviders(
      <Dialog open={false} onClose={() => undefined} title="Versteckt">
        <p>Inhalt</p>
      </Dialog>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes on Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Dialog open onClose={onClose} title="Schließen">
        <button type="button">Aktion</button>
      </Dialog>,
    );

    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes when the close button is used', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Dialog open onClose={onClose} title="Schließen">
        <p>Inhalt</p>
      </Dialog>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Dialog schließen' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('traps focus inside the dialog', async () => {
    renderWithProviders(
      <Dialog open onClose={() => undefined} title="Fokus" showCloseButton={false}>
        <button type="button">Erste</button>
        <button type="button">Zweite</button>
      </Dialog>,
    );

    const first = screen.getByRole('button', { name: 'Erste' });
    const second = screen.getByRole('button', { name: 'Zweite' });

    first.focus();
    await userEvent.tab();
    expect(second).toHaveFocus();

    await userEvent.tab();
    expect(first).toHaveFocus();

    await userEvent.tab({ shift: true });
    expect(second).toHaveFocus();
  });

  it('closes on backdrop interaction but keeps the dialog open on inner clicks', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Dialog open onClose={onClose} title="Backdrop">
        <p>Inhalt</p>
      </Dialog>,
    );

    await userEvent.click(screen.getByText('Inhalt'));
    expect(onClose).not.toHaveBeenCalled();

    await userEvent.click(screen.getByTestId('dialog-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders footer actions', () => {
    renderWithProviders(
      <Dialog
        open
        onClose={() => undefined}
        title="Mit Fußzeile"
        footer={<Button>Speichern</Button>}
      >
        <p>Inhalt</p>
      </Dialog>,
    );
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeInTheDocument();
  });
});

describe('ConfirmDialog', () => {
  it('calls the confirm handler and shows both actions', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    renderWithProviders(
      <ConfirmDialog
        open
        title="Löschen bestätigen"
        description="Nicht umkehrbar"
        confirmLabel="Löschen"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Löschen' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: 'Abbrechen' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});

describe('Drawer', () => {
  it('renders a modal dialog and closes on Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Drawer open onClose={onClose} title="Navigation" side="left">
        <a href="/dashboard">Dashboard</a>
      </Drawer>,
    );

    const drawer = screen.getByRole('dialog');
    expect(drawer).toHaveAccessibleName('Navigation');
    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument();

    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('Tooltip', () => {
  it('shows on focus, hides on blur and links via aria-describedby', async () => {
    renderWithProviders(
      <Tooltip content="Zusatzinformation">
        <button type="button">Info</button>
      </Tooltip>,
    );

    const trigger = screen.getByRole('button', { name: 'Info' });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

    await userEvent.tab();
    expect(trigger).toHaveFocus();
    const tooltip = screen.getByRole('tooltip');
    expect(tooltip).toHaveTextContent('Zusatzinformation');
    expect(trigger).toHaveAccessibleDescription('Zusatzinformation');

    await userEvent.tab();
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('shows on hover', async () => {
    renderWithProviders(
      <Tooltip content="Hover-Text">
        <button type="button">Hover</button>
      </Tooltip>,
    );

    await userEvent.hover(screen.getByRole('button', { name: 'Hover' }));
    expect(screen.getByRole('tooltip')).toHaveTextContent('Hover-Text');
  });
});
