import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { IrisPanel } from '../iris-panel';

const push = vi.fn();
vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push, replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

function answerWith(body: unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    ),
  );
}

describe('IrisPanel', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    push.mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('greets while the topbar holds it open', () => {
    renderWithProviders(<IrisPanel open onClose={vi.fn()} />);
    expect(screen.getByRole('dialog', { name: 'Iris' })).toBeInTheDocument();
    expect(screen.getByText(/ich bin Iris/i)).toBeInTheDocument();
  });

  it('renders nothing while closed', () => {
    renderWithProviders(<IrisPanel open={false} onClose={vi.fn()} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows the answer, and the button it offers opens that screen and closes the panel', async () => {
    answerWith({
      available: true,
      text: 'Öffnen Sie die Integrationen und wählen Sie Postfach verbinden.',
      open: '/integrations',
      handoff: false,
      reason: '',
    });
    const onClose = vi.fn();
    renderWithProviders(<IrisPanel open onClose={onClose} />);
    await userEvent.type(
      screen.getByLabelText('Frage an Iris …'),
      'Wie verbinde ich mein Postfach?',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Senden' }));

    expect(await screen.findByText(/Postfach verbinden/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Öffnen: / }));
    expect(push).toHaveBeenCalledWith('/integrations');
    expect(onClose).toHaveBeenCalled();
  });

  it('says plainly that the assistant is not set up instead of inventing an answer', async () => {
    answerWith({ available: false, text: '', open: '', handoff: false, reason: 'NOT_CONFIGURED' });
    renderWithProviders(<IrisPanel open onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Frage an Iris …'), 'Hallo');
    await userEvent.click(screen.getByRole('button', { name: 'Senden' }));

    await waitFor(() => expect(screen.getByText(/noch nicht eingerichtet/)).toBeInTheDocument());
  });
});
