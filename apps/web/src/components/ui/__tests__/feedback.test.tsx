import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { EmptyState, ErrorState, ProgressBar, SkeletonTable, Spinner } from '../feedback';
import { FileDropzone, UploadList } from '../file-dropzone';
import { ToastProvider, useToast, type ToastApi } from '../toast';
import { Button } from '../button';
import { ApiError } from '@/lib/api/errors';

function ToastTrigger(): React.JSX.Element {
  const toast = useToast();
  return (
    <div>
      <Button onClick={() => toast.success('Gespeichert', 'Der Vorgang wurde gespeichert.')}>
        Erfolg zeigen
      </Button>
      <Button onClick={() => toast.error('Fehlgeschlagen')}>Fehler zeigen</Button>
    </div>
  );
}

describe('Toast', () => {
  it('announces success messages through a polite live region', async () => {
    renderWithProviders(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Erfolg zeigen' }));

    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Gespeichert');
    expect(status).toHaveAttribute('aria-live', 'polite');
  });

  it('announces errors assertively', async () => {
    renderWithProviders(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Fehler zeigen' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Fehlgeschlagen');
    expect(alert).toHaveAttribute('aria-live', 'assertive');
  });

  it('can be dismissed', async () => {
    renderWithProviders(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Erfolg zeigen' }));
    const toast = await screen.findByRole('status');

    await userEvent.click(screen.getByRole('button', { name: 'Ausblenden' }));
    await waitFor(() => expect(toast).not.toBeInTheDocument());
  });

  it('is safe to use without a provider (no-op API)', () => {
    let captured: ToastApi | null = null;
    function Probe(): null {
      captured = useToast();
      return null;
    }
    render(<Probe />);
    expect(captured).not.toBeNull();
    expect((captured as unknown as ToastApi).show({ title: 'x' })).toBe('');
    expect((captured as unknown as ToastApi).dismiss('x')).toBeUndefined();
  });
});

describe('EmptyState and ErrorState', () => {
  it('renders an empty state with title, description and actions', () => {
    renderWithProviders(
      <EmptyState
        title="Keine Dokumente"
        description="Laden Sie ein Dokument hoch."
        actions={<Button>Hochladen</Button>}
      />,
    );

    expect(screen.getByText('Keine Dokumente')).toBeInTheDocument();
    expect(screen.getByText('Laden Sie ein Dokument hoch.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hochladen' })).toBeInTheDocument();
  });

  it('maps API errors to localized messages and shows the correlation id', () => {
    const error = new ApiError({
      kind: 'network',
      code: 'NETWORK',
      correlationId: 'corr-1234',
    });
    renderWithProviders(<ErrorState error={error} onRetry={() => undefined} />);

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/nicht erreichbar/i)).toBeInTheDocument();
    expect(screen.getByText('corr-1234')).toBeInTheDocument();
  });

  it('calls the retry handler', async () => {
    const onRetry = vi.fn();
    renderWithProviders(<ErrorState error={new ApiError({ kind: 'server' })} onRetry={onRetry} />);

    await userEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe('Progress and Skeleton', () => {
  it('exposes progressbar semantics with a readable value text', () => {
    renderWithProviders(<ProgressBar value={40} max={80} label="Upload" showValue />);
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '40');
    expect(bar).toHaveAttribute('aria-valuemax', '80');
    expect(bar).toHaveAttribute('aria-valuetext', '50 Prozent abgeschlossen');
  });

  it('marks indeterminate progress as busy instead of guessing a value', () => {
    renderWithProviders(<ProgressBar indeterminate label="Wird geladen" />);
    const bar = screen.getByRole('progressbar');
    expect(bar).not.toHaveAttribute('aria-valuenow');
    expect(bar).toHaveAttribute('aria-busy', 'true');
  });

  it('provides accessible loading indicators', () => {
    renderWithProviders(
      <>
        <Spinner label="Bitte warten" />
        <SkeletonTable rows={2} columns={2} label="Tabelle lädt" />
      </>,
    );

    expect(screen.getByText('Bitte warten')).toBeInTheDocument();
    expect(screen.getByText('Tabelle lädt')).toBeInTheDocument();
  });
});

describe('FileDropzone', () => {
  it('accepts files with allowed extensions', async () => {
    const onSelect = vi.fn();
    renderWithProviders(<FileDropzone onSelect={onSelect} />);

    const input = screen.getByLabelText('Dateien auswählen');
    const file = new File(['inhalt'], 'rechnung.pdf', { type: 'application/pdf' });
    await userEvent.upload(input, file);

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0]?.[0]).toHaveLength(1);
  });

  it('rejects unsupported file types with an announced message', async () => {
    const onSelect = vi.fn();
    renderWithProviders(<FileDropzone onSelect={onSelect} />);

    const input = screen.getByLabelText('Dateien auswählen');
    await userEvent.upload(
      input,
      new File(['x'], 'schadsoftware.exe', { type: 'application/x-msdownload' }),
      {
        applyAccept: false,
      },
    );

    expect(onSelect).not.toHaveBeenCalled();
    expect(await screen.findByText(/nicht unterstützt/)).toBeInTheDocument();
  });

  it('rejects files above the size limit', async () => {
    const onSelect = vi.fn();
    renderWithProviders(<FileDropzone onSelect={onSelect} maxSizeMb={0.0001} />);

    const input = screen.getByLabelText('Dateien auswählen');
    await userEvent.upload(
      input,
      new File([new Uint8Array(2048)], 'gross.pdf', { type: 'application/pdf' }),
    );

    expect(onSelect).not.toHaveBeenCalled();
    expect(await screen.findByText(/überschreitet/)).toBeInTheDocument();
  });

  it('lists queued uploads with progress and cancel actions', async () => {
    const onCancel = vi.fn();
    renderWithProviders(
      <UploadList
        items={[
          { id: '1', name: 'a.pdf', size: 1024, progress: 30, status: 'uploading' },
          {
            id: '2',
            name: 'b.pdf',
            size: 2048,
            progress: 0,
            status: 'error',
            error: 'Netzwerkfehler',
          },
        ]}
        onCancel={onCancel}
        onRetry={() => undefined}
        onRemove={() => undefined}
      />,
    );

    expect(screen.getByText('a.pdf')).toBeInTheDocument();
    expect(screen.getByText('Netzwerkfehler')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /Upload von a\.pdf abbrechen/ }));
    expect(onCancel).toHaveBeenCalledWith('1');
  });
});
