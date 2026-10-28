import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { act, renderWithProviders, screen, waitFor, within } from '@/test/render';
import { api } from '@/lib/api';
import type { PdfPage } from '@/lib/capture/images-to-pdf';
import { CapturePanel } from '../capture-panel';
import { QuickCapture } from '../quick-capture';

vi.mock('@/lib/api', () => ({
  api: { documents: { upload: vi.fn(), get: vi.fn() } },
}));

const documents = api.documents as unknown as Record<'upload' | 'get', ReturnType<typeof vi.fn>>;
const page: PdfPage = { jpeg: new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 0xff, 0xd9]), width: 100, height: 140 };
const summary = (id: string, title: string) => ({ id, title, status: 'received', documentType: '', receivedAt: '2026-10-15T10:00:00Z', channel: 'upload' });

function shoot(container: HTMLElement, names: string[]): void {
  const input = container.querySelector('input[type=file][capture]') as HTMLInputElement;
  const files = names.map((name) => new File(['x'], name, { type: 'image/jpeg' }));
  Object.defineProperty(input, 'files', { value: files, configurable: true });
  input.dispatchEvent(new Event('change', { bubbles: true }));
}

describe('CapturePanel', () => {
  beforeEach(() => {
    Object.values(documents).forEach((fn) => fn.mockReset());
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('puts the pages in order, saves them as ONE pdf, and then says what became of the document', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    documents.upload.mockResolvedValue(summary('doc-1', 'Scan.pdf'));
    documents.get.mockResolvedValueOnce({ ...summary('doc-1', 'Scan.pdf'), status: 'processing' });
    documents.get.mockResolvedValue({ ...summary('doc-1', 'Scan.pdf'), status: 'needs_review' });
    const convert = vi.fn(async (_file: File) => page);
    const { container } = renderWithProviders(<CapturePanel convert={convert} />);

    shoot(container, ['a.jpg', 'b.jpg', 'c.jpg']);
    const list = await screen.findByRole('list', { name: 'Aufgenommene Seiten' });
    await user.click(screen.getByRole('button', { name: 'Seite 1 nach unten' }));
    await user.click(screen.getByRole('button', { name: 'Seite 3 entfernen' }));
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Als ein Dokument speichern (2 Seiten)' }));
    await waitFor(() => expect(documents.upload).toHaveBeenCalledTimes(1));
    const file = documents.upload.mock.calls[0]![0] as File;
    expect(file.type).toBe('application/pdf');
    expect(file.name).toMatch(/^Scan-.*\.pdf$/);
    expect(convert.mock.calls.map((c) => (c[0] as File).name)).toEqual(['b.jpg', 'a.jpg']);
    expect(await screen.findByText('Gespeichert. Das Dokument wird jetzt gelesen.')).toBeInTheDocument();
    expect(screen.getByText('Wird gelesen …')).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(await screen.findByRole('link', { name: 'Zur Prüfung' })).toHaveAttribute('href', '/review');
    expect(screen.getByRole('link', { name: 'Dokument öffnen' })).toHaveAttribute('href', '/documents/doc-1');
  });

  it('tells the recognised type when the document is settled', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    documents.upload.mockResolvedValue(summary('doc-2', 'IMG.jpg'));
    documents.get.mockResolvedValue({ ...summary('doc-2', 'IMG.jpg'), status: 'processed', typeCode: 'rechnung', typeNames: { de: 'Rechnung' } });
    const { container } = renderWithProviders(<CapturePanel convert={async () => page} />);

    shoot(container, ['a.jpg']);
    await user.click(await screen.findByRole('button', { name: 'Speichern (1)' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(await screen.findByText('Erkannt als: Rechnung.')).toBeInTheDocument();
  });

  it('saves every page as a document of its own on request, without making a pdf', async () => {
    const user = userEvent.setup();
    documents.upload.mockImplementation(async (file: File) => summary(`id-${file.name}`, file.name));
    documents.get.mockResolvedValue({ ...summary('x', 'x'), status: 'processed' });
    const convert = vi.fn(async (_file: File) => page);
    const { container } = renderWithProviders(<CapturePanel convert={convert} />);

    shoot(container, ['a.jpg', 'b.jpg']);
    await user.click(await screen.findByRole('checkbox', { name: 'Jede Seite als eigenes Dokument speichern' }));
    await user.click(screen.getByRole('button', { name: 'Speichern (2)' }));

    await waitFor(() => expect(documents.upload).toHaveBeenCalledTimes(2));
    expect(convert).not.toHaveBeenCalled();
    expect(documents.upload.mock.calls.map((c) => (c[0] as File).name)).toEqual(['a.jpg', 'b.jpg']);
  });

  it('keeps the pages and says so when saving fails', async () => {
    const user = userEvent.setup();
    documents.upload.mockRejectedValue(new Error('network'));
    const { container } = renderWithProviders(<CapturePanel convert={async () => page} />);

    shoot(container, ['a.jpg', 'b.jpg']);
    await user.click(await screen.findByRole('button', { name: 'Als ein Dokument speichern (2 Seiten)' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Aufgenommene Seiten' })).toBeInTheDocument();
  });
});

describe('QuickCapture', () => {
  it('is on every screen as a button and opens the capture panel in place', async () => {
    const user = userEvent.setup();
    renderWithProviders(<QuickCapture />);
    await user.click(screen.getByRole('button', { name: 'Dokument fotografieren oder scannen' }));
    expect(await screen.findByRole('dialog', { name: 'Dokument aufnehmen' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erste Seite fotografieren' })).toBeInTheDocument();
  });
});
