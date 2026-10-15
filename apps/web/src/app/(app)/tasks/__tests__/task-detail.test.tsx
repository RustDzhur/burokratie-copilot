import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import TaskPage from '../[id]/page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/tasks/t1',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 't1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const calls: { url: string; body: string | null }[] = [];

function serve(checklist: readonly object[], completeResponse?: { status: number; body: object }): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, body: typeof init?.body === 'string' ? init.body : null });
      if (url.includes('/complete') && completeResponse) {
        return new Response(JSON.stringify(completeResponse.body), { status: completeResponse.status, headers: { 'Content-Type': 'application/json' } });
      }
      if (url.includes('/tasks/t1')) {
        return new Response(
          JSON.stringify({
            task: {
              id: 't1', title: 'BWA prüfen', status: 'NEW', priority: 'NORMAL', requiresApproval: false,
              completionCriteria: 'Die BWA wurde geprüft', sourceDocumentId: 'doc-9', caseId: null,
            },
            checklist,
            assignees: [], comments: [], subtasks: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('TaskPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows the criterion, the checklist and the source document, and ticking an item tells the server', async () => {
    const user = userEvent.setup();
    serve([{ id: 'c1', label: 'Summen prüfen', required: true, done: false }]);
    renderWithProviders(<TaskPage />);

    expect(await screen.findByText('Die BWA wurde geprüft')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Zum Dokument' })).toHaveAttribute('href', '/documents/doc-9');
    await user.click(screen.getByRole('checkbox', { name: /Summen prüfen/ }));
    await waitFor(() => expect(calls.some((call) => call.url.includes('/tasks/checklist/c1/tick') && call.url.includes('done=true'))).toBe(true));
  });

  it('explains an open required item and finishes knowingly only with a reason', async () => {
    const user = userEvent.setup();
    serve([{ id: 'c1', label: 'Summen prüfen', required: true, done: false }]);
    renderWithProviders(<TaskPage />);
    await screen.findByText('Die BWA wurde geprüft');

    expect(screen.getByText(/1 Pflichtpunkt ist noch offen/)).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Trotzdem abschließen' }));
    await user.click(screen.getByRole('button', { name: 'Aufgabe abschließen' }));
    expect(screen.getByText('Bitte eine Begründung angeben.')).toBeInTheDocument();
    expect(calls.some((call) => call.url.includes('/complete'))).toBe(false);

    await user.type(screen.getByLabelText('Begründung'), 'Wird später geprüft');
    await user.click(screen.getByRole('button', { name: 'Aufgabe abschließen' }));
    await waitFor(() => expect(calls.some((call) => call.url.includes('/complete') && (call.body ?? '').includes('"acknowledgeOpenItems":true'))).toBe(true));
  });

  it('shows the server\'s own reason when finishing is refused', async () => {
    const user = userEvent.setup();
    serve([], { status: 409, body: { code: 'CONFLICT', message: 'This task requires an approval that has not been given yet' } });
    renderWithProviders(<TaskPage />);
    await screen.findByText('Die BWA wurde geprüft');

    await user.click(screen.getByRole('button', { name: 'Aufgabe abschließen' }));
    expect(await screen.findByText(/requires an approval that has not been given yet/)).toBeInTheDocument();
  });
});
