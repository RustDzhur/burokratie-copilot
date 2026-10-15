import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { MergeDialog } from '../merge-dialog';

const calls: { url: string; method: string; body: string | null }[] = [];

function serve(groups: readonly object[]): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : null });
      const body = url.includes('/merge-candidates') ? groups : { documentId: 'new', filename: 'Antrag.pdf' };
      return new Response(JSON.stringify(body), { status: url.endsWith('/merge') ? 201 : 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

const group = { documentIds: ['d1', 'd2', 'd3'], filenames: ['IMG_1.jpg', 'IMG_2.jpg', 'IMG_3.jpg'], suggestedTitle: 'Scan 2026-10-15 10-00.pdf' };

describe('MergeDialog', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('lists the proposed pages, lets the person change the order and the members, and merges only what is kept, in that order', async () => {
    const user = userEvent.setup();
    serve([group]);
    const onMerged = vi.fn();
    renderWithProviders(<MergeDialog open onClose={vi.fn()} onMerged={onMerged} />);

    expect(await screen.findByText('IMG_1.jpg')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'IMG_1.jpg nach unten' }));
    await user.click(screen.getByRole('checkbox', { name: /IMG_3.jpg/ }));
    await user.clear(screen.getByLabelText('Name des Dokuments'));
    await user.type(screen.getByLabelText('Name des Dokuments'), 'Antrag');
    await user.click(screen.getByRole('button', { name: 'Diese Gruppe zusammenführen' }));

    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/documents/merge') && c.method === 'POST')).toBe(true));
    const post = calls.find((c) => c.url.endsWith('/documents/merge'));
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ documentIds: ['d2', 'd1'], title: 'Antrag' });
    await waitFor(() => expect(onMerged).toHaveBeenCalled());
  });

  it('says so when nothing belongs together, and merges nothing', async () => {
    serve([]);
    renderWithProviders(<MergeDialog open onClose={vi.fn()} onMerged={vi.fn()} />);
    expect(await screen.findByText('Keine zusammengehörigen Seiten gefunden.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('a group with fewer than two kept pages cannot be merged', async () => {
    const user = userEvent.setup();
    serve([{ ...group, documentIds: ['d1', 'd2'], filenames: ['a.jpg', 'b.jpg'] }]);
    renderWithProviders(<MergeDialog open onClose={vi.fn()} onMerged={vi.fn()} />);
    await screen.findByText('a.jpg');
    await user.click(screen.getByRole('checkbox', { name: /b.jpg/ }));
    expect(screen.getByRole('button', { name: 'Diese Gruppe zusammenführen' })).toBeDisabled();
  });
});
