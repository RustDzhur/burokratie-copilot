import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor, within } from '@/test/render';
import { MultiPageCapture } from '../multi-page-capture';

const page = { jpeg: new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 0xff, 0xd9]), width: 100, height: 140 };

function shoot(container: HTMLElement, names: string[]): void {
  const input = container.querySelector('input[type=file]') as HTMLInputElement;
  const files = names.map((name) => new File(['x'], name, { type: 'image/jpeg' }));
  Object.defineProperty(input, 'files', { value: files, configurable: true });
  input.dispatchEvent(new Event('change', { bubbles: true }));
}

describe('MultiPageCapture', () => {
  it('collects the pages, lets them be put in order or removed, and hands on one PDF', async () => {
    const user = userEvent.setup();
    const onPdf = vi.fn();
    const convert = vi.fn(async (_file: File) => page);
    const { container } = renderWithProviders(<MultiPageCapture onPdf={onPdf} convert={convert} />);

    shoot(container, ['a.jpg', 'b.jpg', 'c.jpg']);
    const list = await screen.findByRole('list', { name: 'Aufgenommene Seiten' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(3);

    await user.click(screen.getByRole('button', { name: 'Seite 1 nach unten' }));
    expect(within(list).getAllByRole('listitem')[0]).toHaveTextContent('b.jpg');
    await user.click(screen.getByRole('button', { name: 'Seite 3 entfernen' }));
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Als ein PDF hochladen (2 Seiten)' }));
    await waitFor(() => expect(onPdf).toHaveBeenCalledTimes(1));
    const file = onPdf.mock.calls[0]![0] as File;
    expect(file.type).toBe('application/pdf');
    expect(file.name).toMatch(/^Scan-\d{4}-\d{2}-\d{2}\.pdf$/);
    expect(convert).toHaveBeenCalledTimes(2);
    expect(convert.mock.calls.map((call) => (call[0] as File).name)).toEqual(['b.jpg', 'a.jpg']);
    expect(screen.queryByRole('list', { name: 'Aufgenommene Seiten' })).not.toBeInTheDocument();
  });

  it('says so when a page cannot be converted, and keeps the pages', async () => {
    const user = userEvent.setup();
    const onPdf = vi.fn();
    const { container } = renderWithProviders(<MultiPageCapture onPdf={onPdf} convert={async () => Promise.reject(new Error('bad'))} />);
    shoot(container, ['a.jpg']);
    await user.click(await screen.findByRole('button', { name: /Als ein PDF hochladen/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('nicht zu einem PDF');
    expect(onPdf).not.toHaveBeenCalled();
    expect(screen.getByRole('list', { name: 'Aufgenommene Seiten' })).toBeInTheDocument();
  });
});
