import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { IrisPanel } from '../iris-panel';

vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const PROPOSAL_ID = '11111111-1111-1111-1111-111111111111';

/** Serves the assistant endpoints; the handler returns the JSON body or 204 for undefined. */
function serve(handler: (url: string, method: string) => unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.includes('/auth/csrf')) {
        return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      const body = handler(url, method);
      if (body === undefined) return new Response(null, { status: 204 });
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
}

async function sendQuestion(question: string): Promise<void> {
  await userEvent.type(screen.getByLabelText('Frage an Iris …'), question);
  await userEvent.click(screen.getByRole('button', { name: 'Senden' }));
}

describe('IrisPanel', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
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

  it('shows the tool result of a plain answer', async () => {
    serve((url, method) => {
      expect(method).toBe('POST');
      expect(url).toContain('/assistant/ask');
      return {
        kind: 'ANSWER',
        message: "Ergebnis von 'list_open_tasks'",
        result: { tasks: [{ title: 'Rechnung prüfen' }] },
        code: null,
      };
    });
    renderWithProviders(<IrisPanel open onClose={vi.fn()} />);

    await sendQuestion('Was ist offen?');

    expect(await screen.findByText("Ergebnis von 'list_open_tasks'")).toBeInTheDocument();
    expect(screen.getByText(/"Rechnung prüfen"/)).toBeInTheDocument();
  });

  it('says plainly when the assistant is not set up instead of inventing an answer', async () => {
    serve(() => ({ kind: 'REFUSED', message: '', code: 'NOT_CONFIGURED' }));
    renderWithProviders(<IrisPanel open onClose={vi.fn()} />);

    await sendQuestion('Hallo');

    await waitFor(() => expect(screen.getByText(/noch nicht eingerichtet/)).toBeInTheDocument());
  });

  it('turns an effective tool into a proposal with confirm and reject buttons', async () => {
    const calls: string[] = [];
    serve((url, method) => {
      if (url.includes('/assistant/ask')) {
        return {
          kind: 'PROPOSAL',
          message: "Der Assistent schlägt vor, 'create_task' auszuführen.",
          proposalId: PROPOSAL_ID,
          tool: 'create_task',
          preview: 'Werkzeug: create_task\n  title: Rechnung prüfen',
          result: null,
          code: null,
        };
      }
      if (url.includes(`/assistant/proposals/${PROPOSAL_ID}/confirm`)) {
        calls.push(`confirm:${method}`);
        return { message: 'Der Vorschlag wurde bestätigt und ausgeführt', result: { created: true } };
      }
      if (url.includes(`/assistant/proposals/${PROPOSAL_ID}/reject`)) {
        calls.push(`reject:${method}`);
        return undefined;
      }
      throw new Error(`unexpected call: ${url}`);
    });
    const person = userEvent.setup();
    renderWithProviders(<IrisPanel open onClose={vi.fn()} />);

    await person.type(screen.getByLabelText('Frage an Iris …'), 'Lege eine Aufgabe an');
    await person.click(screen.getByRole('button', { name: 'Senden' }));

    // The preview tells the person what would happen; nothing has happened yet.
    expect(await screen.findByText(/Rechnung prüfen/)).toBeInTheDocument();
    const confirmButton = screen.getByRole('button', { name: 'Bestätigen' });
    const rejectButton = screen.getByRole('button', { name: 'Ablehnen' });
    expect(confirmButton).toBeInTheDocument();
    expect(rejectButton).toBeInTheDocument();

    await person.click(confirmButton);
    await waitFor(() => expect(calls).toContain(`confirm:POST`));
    expect(await screen.findByText('Ausgeführt. Die Aktion wurde bestätigt.')).toBeInTheDocument();
    expect(screen.getByText(/"created": true/)).toBeInTheDocument();
  });

  it('rejects a proposal without applying it', async () => {
    const calls: string[] = [];
    serve((url) => {
      if (url.includes('/assistant/ask')) {
        return {
          kind: 'PROPOSAL',
          message: 'Der Assistent schlägt etwas vor.',
          proposalId: PROPOSAL_ID,
          tool: 'create_task',
          preview: 'Werkzeug: create_task',
          result: null,
          code: null,
        };
      }
      if (url.includes('/reject')) {
        calls.push('reject');
        return undefined;
      }
      if (url.includes('/confirm')) {
        calls.push('confirm');
        return { message: '', result: null };
      }
      throw new Error(`unexpected call: ${url}`);
    });
    const person = userEvent.setup();
    renderWithProviders(<IrisPanel open onClose={vi.fn()} />);

    await sendQuestion('Lege eine Aufgabe an');
    await person.click(await screen.findByRole('button', { name: 'Ablehnen' }));

    expect(await screen.findByText('Der Vorschlag wurde abgelehnt.')).toBeInTheDocument();
    expect(calls).toEqual(['reject']);
  });
});
