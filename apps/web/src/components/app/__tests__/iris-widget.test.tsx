import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { IrisWidget } from '../iris-widget';

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
    vi.fn(async () =>
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    ),
  );
}

describe('IrisWidget', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    push.mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('opens from the corner button and greets', async () => {
    renderWithProviders(<IrisWidget />);
    await userEvent.click(screen.getByRole('button', { name: 'Iris öffnen' }));
    expect(screen.getByRole('dialog', { name: 'Iris' })).toBeInTheDocument();
    expect(screen.getByText(/ich bin Iris/i)).toBeInTheDocument();
  });

  it('shows the answer, and the button it offers opens that screen', async () => {
    answerWith({
      available: true,
      text: 'Öffnen Sie die Integrationen und wählen Sie Postfach verbinden.',
      open: '/integrations',
      handoff: false,
      reason: '',
    });
    renderWithProviders(<IrisWidget />);
    await userEvent.click(screen.getByRole('button', { name: 'Iris öffnen' }));
    await userEvent.type(screen.getByLabelText('Frage an Iris …'), 'Wie verbinde ich mein Postfach?');
    await userEvent.click(screen.getByRole('button', { name: 'Senden' }));

    expect(await screen.findByText(/Postfach verbinden/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Öffnen: / }));
    expect(push).toHaveBeenCalledWith('/integrations');
  });

  it('says plainly that the assistant is not set up instead of inventing an answer', async () => {
    answerWith({ available: false, text: '', open: '', handoff: false, reason: 'NOT_CONFIGURED' });
    renderWithProviders(<IrisWidget />);
    await userEvent.click(screen.getByRole('button', { name: 'Iris öffnen' }));
    await userEvent.type(screen.getByLabelText('Frage an Iris …'), 'Hallo');
    await userEvent.click(screen.getByRole('button', { name: 'Senden' }));

    await waitFor(() => expect(screen.getByText(/noch nicht eingerichtet/)).toBeInTheDocument());
  });
});
